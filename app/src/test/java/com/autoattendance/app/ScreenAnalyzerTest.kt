package com.autoattendance.app

import com.autoattendance.app.automation.Labels
import com.autoattendance.app.automation.ScreenAnalyzer
import com.autoattendance.app.automation.ScreenState
import com.autoattendance.app.automation.UiNode
import com.autoattendance.app.data.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAnalyzerTest {

    private val labels = Labels("Sign In", "Sign Out", listOf("Time elapsed", "Shift", "Hello", "Regularize"))

    private fun analyze(vararg roots: UiNode) = ScreenAnalyzer.analyze(roots.toList(), labels)

    private fun text(t: String) = UiNode(text = t)
    private fun button(label: String, enabled: Boolean = true) =
        UiNode(clickable = true, enabled = enabled, children = listOf(text(label)))

    /** Mirrors the attendance app's home screen from the reference screenshot. */
    private fun home(button: UiNode, vararg extra: UiNode) = UiNode(
        children = listOf(
            text("Hello Ashok"), text("Friday | General Shift"), text("Time elapsed today: 10h 21m"),
            button, text("01 Exception day"), UiNode(clickable = true, children = listOf(text("Regularize"))),
            text("Home"), text("Action"),
        ) + extra,
    )

    /** Mirrors the "Sign-Out Details" screen. [shown] is what the location dropdown displays. */
    private fun details(
        op: String = "Sign Out",
        shown: String = "Please select your location",
        submit: UiNode = button(op),
        remarks: UiNode = UiNode(editable = true, hint = "Add Remarks"),
    ) = UiNode(
        children = listOf(
            UiNode(clickable = true, contentDescription = "Back"),
            text("${op.replace(' ', '-')} Details"),
            text("Your Current Location Accuracy: 20m"), text("Date"), text("23 October 2026"),
            text("Attendance Scheme"), text("Friday| General Attendance Scheme"),
            text("$op Location"),
            UiNode(clickable = true, children = listOf(text(shown), UiNode(contentDescription = "▼"))),
            text("Remarks"), remarks, text("0/200 characters"),
            submit,
        ),
    )

    /** Mirrors the "Select your Sign-Out Location" list. */
    private fun locationDialog(op: String = "Sign Out", vararg options: String = arrayOf("Work from Home", "Office", "Client Location", "On-Duty")) =
        UiNode(
            children = listOf(
                UiNode(children = listOf(text("Select your ${op.replace(' ', '-')} Location"), UiNode(clickable = true, contentDescription = "Close"))),
                UiNode(editable = true, hint = "Search"),
                UiNode(children = options.map { button(it) }),
            ),
        )

    // ---- Home ----------------------------------------------------------------------------------

    @Test
    fun `signed in when Sign Out is visible on home`() {
        val signOut = button("Sign Out")
        val a = analyze(home(signOut))
        assertEquals(ScreenState.HOME_SIGNED_IN, a.state)
        assertSame(signOut, a.actionButton)
    }

    @Test
    fun `signed out when Sign In is visible on home`() {
        val signIn = button("Sign In")
        val a = analyze(home(signIn))
        assertEquals(ScreenState.HOME_SIGNED_OUT, a.state)
        assertSame(signIn, a.actionButton)
    }

    @Test
    fun `label matching ignores case and spacing`() {
        assertEquals(ScreenState.HOME_SIGNED_IN, analyze(home(button("SIGN OUT"))).state)
        assertEquals(ScreenState.HOME_SIGNED_IN, analyze(home(UiNode(clickable = true, contentDescription = "Sign-out"))).state)
    }

    @Test
    fun `login screen is detected and never treated as signed out`() {
        val login = UiNode(
            children = listOf(
                UiNode(editable = true, hint = "Username"),
                UiNode(editable = true, password = true, hint = "Password"),
                button("Sign In"),
            ),
        )
        assertEquals(ScreenState.LOGIN_SCREEN, analyze(login).state)
    }

    @Test
    fun `loading spinner or disabled button means loading`() {
        assertEquals(ScreenState.LOADING, analyze(UiNode(className = "android.widget.ProgressBar")).state)
        assertEquals(ScreenState.LOADING, analyze(home(button("Sign Out", enabled = false))).state)
    }

    @Test
    fun `network error screen`() {
        val err = UiNode(children = listOf(text("No internet connection"), button("Retry")))
        assertEquals(ScreenState.ERROR, analyze(err).state)
    }

    @Test
    fun `sign out outside the attendance home is not clicked`() {
        val profileMenu = UiNode(children = listOf(text("My profile"), button("Sign Out")))
        val a = analyze(profileMenu)
        assertEquals(ScreenState.UNKNOWN, a.state)
        assertNull(a.actionButton)
    }

    @Test
    fun `two attendance controls are ambiguous`() {
        assertEquals(ScreenState.AMBIGUOUS, analyze(home(button("Sign Out"), button("Sign Out"))).state)
        assertEquals(ScreenState.AMBIGUOUS, analyze(home(button("Sign Out"), button("Sign In"))).state)
    }

    @Test
    fun `confirmation dialog only offers generic answers`() {
        val dialog = UiNode(children = listOf(text("Are you sure you want to sign out?"), button("Cancel"), button("Yes")))
        val a = analyze(home(button("Sign Out")), dialog)
        assertEquals(ScreenState.CONFIRM_DIALOG, a.state)
        assertEquals("Yes", a.confirmTargets.single().children.single().text)
    }

    @Test
    fun `unrelated dialogs are never treated as a confirmation`() {
        val update = UiNode(children = listOf(text("Do you want to update the app?"), button("Cancel"), button("OK")))
        val a = analyze(home(button("Sign Out")), update)
        assertFalse(a.state == ScreenState.CONFIRM_DIALOG)
    }

    @Test
    fun `without home markers no screen counts as home`() {
        val noMarkers = Labels("Sign In", "Sign Out", emptyList())
        val a = ScreenAnalyzer.analyze(listOf(home(button("Sign Out"))), noMarkers)
        assertEquals(ScreenState.UNKNOWN, a.state)
        assertNull(a.actionButton)
    }

    // ---- Details form ----------------------------------------------------------------------------

    @Test
    fun `sign out details form with placeholder`() {
        val a = analyze(details())
        assertEquals(ScreenState.DETAILS, a.state)
        assertEquals(Operation.SIGN_OUT, a.form)
        assertNotNull(a.locationField)
        assertNull(a.selectedLocation)
        assertFalse(a.locationSelected("Office"))
        assertNotNull(a.remarksField)
        assertNull(a.remarksText)
        assertEquals("Sign Out", a.actionButton?.children?.single()?.text)
    }

    @Test
    fun `details form shows the selected location`() {
        val a = analyze(details(shown = "Work from Home"))
        assertEquals("Work from Home", a.selectedLocation)
        assertTrue(a.locationSelected("work from home"))
        assertFalse(a.locationSelected("Office"))
    }

    @Test
    fun `sign in details form is recognised as sign in`() {
        val a = analyze(details(op = "Sign In", shown = "Office"))
        assertEquals(ScreenState.DETAILS, a.state)
        assertEquals(Operation.SIGN_IN, a.form)
        assertTrue(a.locationSelected("Office"))
    }

    @Test
    fun `details form with the other operation's button is ambiguous`() {
        val form = details()
        val a = analyze(UiNode(children = listOf(form, button("Sign In"))))
        assertEquals(ScreenState.AMBIGUOUS, a.state)
        assertNull(a.actionButton)
    }

    @Test
    fun `typed remarks are read back but never logged as screen text`() {
        val a = analyze(details(remarks = UiNode(editable = true, hint = "Add Remarks", text = "Leaving early")))
        assertEquals("Leaving early", a.remarksText)
        assertFalse(a.texts.contains("Leaving early"))
    }

    // ---- Location list ---------------------------------------------------------------------------

    @Test
    fun `location dialog lists every option`() {
        val a = analyze(details(), locationDialog())
        assertEquals(ScreenState.LOCATION_DIALOG, a.state)
        assertEquals(Operation.SIGN_OUT, a.form)
        assertEquals(listOf("Work from Home", "Office", "Client Location", "On-Duty"), a.locationOptions.map { it.text })
    }

    @Test
    fun `configured location is matched exactly, never the first entry`() {
        val a = analyze(locationDialog())
        assertEquals("Office", a.optionFor("Office")?.children?.single()?.text)
        assertEquals("On-Duty", a.optionFor("on duty")?.children?.single()?.text)
        assertNull(a.optionFor("Head Office"))
        assertNull(a.optionFor(""))
    }

    @Test
    fun `missing location is reported as unavailable`() {
        val a = analyze(locationDialog("Sign Out", "Work from Home", "Client Location"))
        assertNull(a.optionFor("Office"))
    }

    @Test
    fun `entries sharing one click target are not selectable`() {
        val dialog = UiNode(
            children = listOf(
                text("Select your Sign-In Location"),
                UiNode(clickable = true, children = listOf(text("Office"), text("Work from Home"))),
                button("Client Location"),
            ),
        )
        val a = analyze(dialog)
        assertEquals(Operation.SIGN_IN, a.form)
        assertNull(a.optionFor("Office"))
        assertNotNull(a.optionFor("Client Location"))
    }

    // ---- Wrong-button guard ----------------------------------------------------------------------

    @Test
    fun `button guard never accepts the opposite operation`() {
        assertTrue(ScreenAnalyzer.isButtonFor(button("Sign Out"), Operation.SIGN_OUT, labels))
        assertFalse(ScreenAnalyzer.isButtonFor(button("Sign Out"), Operation.SIGN_IN, labels))
        assertFalse(ScreenAnalyzer.isButtonFor(button("Sign In"), Operation.SIGN_OUT, labels))
        val both = UiNode(clickable = true, children = listOf(text("Sign In"), text("Sign Out")))
        assertFalse(ScreenAnalyzer.isButtonFor(both, Operation.SIGN_OUT, labels))
        assertFalse(ScreenAnalyzer.isButtonFor(button("Regularize"), Operation.SIGN_OUT, labels))
    }
}
