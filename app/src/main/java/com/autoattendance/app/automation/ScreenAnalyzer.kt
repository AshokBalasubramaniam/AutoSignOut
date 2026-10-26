package com.autoattendance.app.automation

import com.autoattendance.app.data.Operation

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isEmpty get() = right <= left || bottom <= top
}

/** Platform-independent snapshot of an accessibility node, so the rules are unit-testable. */
class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val editable: Boolean = false,
    val bounds: Box = Box(0, 0, 0, 0),
    /** The live AccessibilityNodeInfo on device; null in tests. */
    val handle: Any? = null,
    val children: List<UiNode> = emptyList(),
) {
    var parent: UiNode? = null
        private set

    init {
        children.forEach { it.parent = this }
    }
}

enum class ScreenState {
    /** Home screen with the Sign Out button – the user is signed in. */
    HOME_SIGNED_IN,
    /** Home screen with the Sign In button – the user is signed out. */
    HOME_SIGNED_OUT,
    /** "Sign-In Details" / "Sign-Out Details" form: location dropdown, remarks, submit button. */
    DETAILS,
    /** "Select your Sign-Out Location" list. */
    LOCATION_DIALOG,
    LOGIN_SCREEN,
    CONFIRM_DIALOG,
    ERROR,
    LOADING,
    /** More than one candidate control, or controls for both operations – never click in this state. */
    AMBIGUOUS,
    UNKNOWN,
}

data class Labels(val signIn: String, val signOut: String, val homeMarkers: List<String>) {
    fun of(op: Operation) = if (op == Operation.SIGN_IN) signIn else signOut
}

data class LocationOption(val text: String, val target: UiNode)

data class ScreenAnalysis(
    val state: ScreenState,
    /** Operation the details form or location dialog belongs to. */
    val form: Operation? = null,
    /** HOME: the single attendance button. DETAILS: the single submit button. */
    val actionButton: UiNode? = null,
    /** DETAILS: the location dropdown and the texts it currently shows. */
    val locationField: UiNode? = null,
    val locationFieldTexts: List<String> = emptyList(),
    val remarksField: UiNode? = null,
    val remarksText: String? = null,
    /** LOCATION_DIALOG: selectable entries, each with its own distinct clickable target. */
    val locationOptions: List<LocationOption> = emptyList(),
    val confirmTargets: List<UiNode> = emptyList(),
    val texts: List<String> = emptyList(),
) {
    /** Location the dropdown shows, or null while it still shows the "Please select" placeholder. */
    val selectedLocation: String?
        get() = locationFieldTexts.firstOrNull { !ScreenAnalyzer.isPlaceholder(it) }

    fun locationSelected(location: String): Boolean {
        val want = ScreenAnalyzer.normalize(location)
        return want.isNotEmpty() && locationFieldTexts.any { ScreenAnalyzer.normalize(it) == want }
    }

    /** The single dialog entry whose text exactly matches [location]; null if missing or duplicated. */
    fun optionFor(location: String): UiNode? {
        val want = ScreenAnalyzer.normalize(location)
        if (want.isEmpty()) return null
        return locationOptions.filter { ScreenAnalyzer.normalize(it.text) == want }
            .map { it.target }
            .distinctBy { System.identityHashCode(it) }
            .singleOrNull()
    }

    /** Readable screen text for error messages, with GPS coordinates masked. */
    fun summary(max: Int = 12): String =
        texts.take(max).joinToString(" | ") { ScreenAnalyzer.redact(it).take(40) }.ifEmpty { "(no readable text)" }
}

object ScreenAnalyzer {

    private val LOGIN_HINTS = listOf(
        "password", "username", "forgot", "rememberme", "loginwith", "signinwith",
        "enterotp", "employeeid", "emailaddress", "emailid",
    )
    private val STRONG_LOGIN_HINTS = listOf("forgotpassword", "loginwith", "signinwith", "rememberme")
    private val ERROR_HINTS = listOf(
        "nointernet", "noconnection", "networkerror", "somethingwentwrong", "tryagain",
        "unabletoconnect", "timedout", "servererror", "checkyourinternet", "youareoffline",
        "connectionerror", "networkunavailable",
    )
    private val CONFIRM_QUESTIONS = listOf("areyousure", "doyouwant", "doyoureallywant", "confirmsignout", "confirmsignin")

    /** Generic confirmation answers only. A button labelled Sign In / Sign Out is never treated as "Yes". */
    private val CONFIRM_BUTTONS = setOf("yes", "confirm", "ok", "okay", "proceed")

    private val COORDINATE = Regex("""-?\d{1,3}\.\d{4,}""")

    /** Masks GPS coordinates such as "77.3735153" so they never reach the logs. */
    fun redact(s: String): String = COORDINATE.replace(s, "•••")

    /** Lowercase letters/digits only: "SIGN-OUT", "Sign Out" and "sign out" all become "signout". */
    fun normalize(s: String?): String = s?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /** "Please select your location" and similar dropdown placeholders. */
    fun isPlaceholder(s: String?): Boolean = normalize(s).let { it.contains("select") && it.contains("location") }

    fun analyze(roots: List<UiNode>, labels: Labels): ScreenAnalysis {
        val all = roots.flatMap(::flatten)

        // Never collect what the user typed into input fields.
        val texts = all.flatMap { n ->
            if (n.editable || n.password) listOfNotNull(n.hint) else listOfNotNull(n.text, n.contentDescription)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val norm = texts.map(::normalize)
        fun hasAny(hints: List<String>) = norm.any { t -> hints.any { t.contains(it) } }

        val signOut = targetsFor(all, labels.signOut)
        val signIn = targetsFor(all, labels.signIn)
        val markers = labels.homeMarkers.map(::normalize).filter { it.isNotEmpty() }
        // No markers configured means the home screen cannot be proven – never assume it.
        val onHome = markers.isNotEmpty() && norm.any { t -> markers.any { t.contains(it) } }

        // A confirmation must be about attendance ("Are you sure you want to sign out?"), so an
        // unrelated "Do you want to update?" dialog is never answered with Yes/OK.
        val opKeys = listOf(normalize(labels.signIn), normalize(labels.signOut), "attendance").filter { it.isNotEmpty() }
        fun isConfirmQuestion(t: String) = CONFIRM_QUESTIONS.any { t.contains(it) } && opKeys.any { t.contains(it) }

        // Prefer the window (dialog) that actually asks the question, so the button behind it is ignored.
        val dialogRoot = roots.lastOrNull { r ->
            flatten(r).any { n -> isConfirmQuestion(normalize(n.text ?: n.contentDescription)) }
        }
        val confirmTargets = (dialogRoot?.let(::flatten) ?: all)
            .filter { normalize(it.text) in CONFIRM_BUTTONS || normalize(it.contentDescription) in CONFIRM_BUTTONS }
            .mapNotNull(::clickableTarget)
            .distinctBy { System.identityHashCode(it) }

        val dialogTitles = Operation.entries.associateWith { op ->
            val key = normalize(labels.of(op)) + "location"
            all.filter { n -> normalize(n.text ?: n.contentDescription).let { it.contains("select") && it.contains(key) } }
        }.filterValues { it.isNotEmpty() }
        val detailsOps = Operation.entries.filter { op ->
            val key = normalize(labels.of(op))
            norm.any { it.contains(key + "details") || it == key + "location" }
        }

        val base = ScreenAnalysis(state = ScreenState.UNKNOWN, confirmTargets = confirmTargets, texts = texts)
        return when {
            all.any { it.password } ||
                (all.any { it.editable } && hasAny(LOGIN_HINTS)) ||
                hasAny(STRONG_LOGIN_HINTS) -> base.copy(state = ScreenState.LOGIN_SCREEN)

            dialogTitles.size > 1 -> base.copy(state = ScreenState.AMBIGUOUS)
            dialogTitles.size == 1 -> {
                val (op, titles) = dialogTitles.entries.single()
                base.copy(state = ScreenState.LOCATION_DIALOG, form = op, locationOptions = locationOptions(titles.first()))
            }

            dialogRoot != null && confirmTargets.isNotEmpty() -> base.copy(state = ScreenState.CONFIRM_DIALOG)

            detailsOps.size > 1 -> base.copy(state = ScreenState.AMBIGUOUS)
            detailsOps.size == 1 -> details(base, all, detailsOps.single(), labels)

            signOut.size > 1 || signIn.size > 1 ||
                (signOut.isNotEmpty() && signIn.isNotEmpty()) -> base.copy(state = ScreenState.AMBIGUOUS)

            // A Sign Out somewhere other than the attendance home (e.g. a profile/account menu)
            // could mean "log out of the app" – never treat that as the attendance button.
            signOut.size == 1 && !onHome -> base
            signOut.size == 1 -> if (signOut[0].enabled) {
                base.copy(state = ScreenState.HOME_SIGNED_IN, actionButton = signOut[0])
            } else {
                base.copy(state = ScreenState.LOADING)
            }
            signIn.size == 1 && onHome -> if (signIn[0].enabled) {
                base.copy(state = ScreenState.HOME_SIGNED_OUT, actionButton = signIn[0])
            } else {
                base.copy(state = ScreenState.LOADING)
            }

            hasAny(ERROR_HINTS) -> base.copy(state = ScreenState.ERROR)
            texts.isEmpty() || all.any { it.className?.contains("ProgressBar") == true } -> base.copy(state = ScreenState.LOADING)
            else -> base
        }
    }

    /**
     * True only if [target] shows [op]'s label and not the other operation's label. Checked again
     * right before every click so the wrong attendance button can never be pressed.
     */
    fun isButtonFor(target: UiNode, op: Operation, labels: Labels): Boolean {
        fun shows(label: String): Boolean {
            val want = normalize(label)
            return want.isNotEmpty() && flatten(target).any { n ->
                normalize(n.text) == want || normalize(n.contentDescription).let { it == want || it == want + "button" }
            }
        }
        val other = if (op == Operation.SIGN_IN) Operation.SIGN_OUT else Operation.SIGN_IN
        return shows(labels.of(op)) && !shows(labels.of(other))
    }

    private fun details(base: ScreenAnalysis, all: List<UiNode>, op: Operation, labels: Labels): ScreenAnalysis {
        val other = if (op == Operation.SIGN_IN) Operation.SIGN_OUT else Operation.SIGN_IN
        val submit = targetsFor(all, labels.of(op))
        if (submit.size > 1 || targetsFor(all, labels.of(other)).isNotEmpty()) {
            return base.copy(state = ScreenState.AMBIGUOUS, form = op)
        }
        if (submit.isEmpty()) return base.copy(state = ScreenState.LOADING, form = op)
        val button = submit.single()

        // Location dropdown: the placeholder's control, else the first control after the "<op> Location" label.
        val locationKey = normalize(labels.of(op)) + "location"
        val labelIndex = all.indexOfFirst { normalize(it.text ?: it.contentDescription) == locationKey }
        val field = all.firstOrNull { !it.editable && isPlaceholder(it.text ?: it.contentDescription) }
            ?.let(::clickableTarget)
            ?: labelIndex.takeIf { it >= 0 }?.let { i ->
                val labelTarget = clickableTarget(all[i])
                all.drop(i + 1).asSequence()
                    .takeWhile { !it.editable && it !== button }
                    .mapNotNull(::clickableTarget)
                    .firstOrNull { it !== button && it !== labelTarget }
            }
        val fieldTexts = field?.let(::flatten).orEmpty().filter { !it.editable }.let { nodes ->
            nodes.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { nodes.mapNotNull { it.contentDescription?.trim() }.filter { it.isNotEmpty() } }
        }

        // Remarks: an input hinted "remark", else the first input after a "Remarks" label.
        val remarksIndex = all.indexOfFirst { normalize(it.text) == "remarks" }
        val remarks = all.firstOrNull { it.editable && normalize(it.hint).contains("remark") }
            ?: remarksIndex.takeIf { it >= 0 }?.let { i -> all.drop(i + 1).firstOrNull { it.editable } }
        val remarksText = remarks?.text?.takeIf { it.isNotEmpty() && it != remarks.hint }

        return base.copy(
            state = ScreenState.DETAILS, form = op, actionButton = button,
            locationField = field, locationFieldTexts = fieldTexts,
            remarksField = remarks, remarksText = remarksText,
        )
    }

    /**
     * Entries of the location list: texts in the smallest container around the dialog title that holds
     * at least two selectable entries. An entry whose click target is shared with another entry is dropped,
     * since clicking it would not select one specific location.
     */
    private fun locationOptions(title: UiNode): List<LocationOption> {
        val titleTarget = clickableTarget(title)
        fun candidates(scope: UiNode, label: (UiNode) -> String?) = flatten(scope).mapNotNull { n ->
            val t = label(n)?.trim()
            if (n === title || n.editable || t.isNullOrEmpty()) return@mapNotNull null
            clickableTarget(n)?.takeIf { it !== titleTarget }?.let { LocationOption(t, it) }
        }
        // Real text wins; accessibility labels (icons such as "Close") are used only when no entry has text.
        fun candidates(scope: UiNode) =
            candidates(scope) { it.text }.ifEmpty { candidates(scope) { it.text ?: it.contentDescription } }
        var scope: UiNode? = title
        while (scope != null) {
            val found = candidates(scope)
            if (found.size >= 2) {
                val shared = found.groupBy { System.identityHashCode(it.target) }
                    .filterValues { group -> group.map { normalize(it.text) }.distinct().size > 1 }.keys
                return found.filter { System.identityHashCode(it.target) !in shared }
            }
            scope = scope.parent
        }
        return emptyList()
    }

    /** Pre-order list of [node] and all descendants. Iterative, so each poll allocates one list. */
    private fun flatten(node: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<UiNode>().apply { addLast(node) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return out
    }

    /** Distinct clickable controls whose text or accessibility label exactly matches [label]. */
    private fun targetsFor(all: List<UiNode>, label: String): List<UiNode> {
        val want = normalize(label)
        if (want.isEmpty()) return emptyList()
        return all
            .filter { n ->
                normalize(n.text) == want ||
                    normalize(n.contentDescription).let { it == want || it == want + "button" }
            }
            .mapNotNull(::clickableTarget)
            .distinctBy { System.identityHashCode(it) }
    }

    /** The node itself or a close ancestor (button container) that is clickable. */
    private fun clickableTarget(node: UiNode): UiNode? {
        var cur: UiNode? = node
        repeat(MAX_ANCESTOR_HOPS + 1) {
            val n = cur ?: return null
            if (n.clickable) return n
            cur = n.parent
        }
        return null
    }

    private const val MAX_ANCESTOR_HOPS = 3
}
