package dev.maia.app.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G9, the one state that is worth a screen.
 *
 * The reading itself is two binder calls and cannot be made on this box. The
 * decision they feed is four lines and is entirely provable here, which is why
 * it is a function rather than an `if` inside an Activity.
 */
class AssistantRoleTest {

    @Test
    fun `role held and the setting names Maia is the working state`() {
        assertEquals(
            AssistantBinding.Bound,
            assistantBinding(roleHeld = true, namesMaia = true),
        )
    }

    @Test
    fun `role held while the setting names nothing is the state G9 found`() {
        // Pixel 10 Pro, 2026-09-13 18:10: role holder was the spike,
        // Settings.Secure.assistant was the spike's service, and
        // voice_interaction_service was empty. The gesture reached nothing.
        assertEquals(
            AssistantBinding.RoleWithoutBinding,
            assistantBinding(roleHeld = true, namesMaia = false),
        )
    }

    @Test
    fun `not holding the role is not a fault`() {
        // The ordinary state of a fresh install. Nothing to repair and nothing
        // to warn about: the user has simply not granted it.
        assertEquals(
            AssistantBinding.NotHolder,
            assistantBinding(roleHeld = false, namesMaia = false),
        )
    }

    @Test
    fun `an unanswerable role question is unknown, never a fault`() {
        // Below API 29 there is no isRoleHeld, and a thrown query answers
        // nothing either. Rendering that as "you have not granted it" would nag
        // a user who granted it long ago.
        assertEquals(
            AssistantBinding.Unknown,
            assistantBinding(roleHeld = null, namesMaia = false),
        )
    }

    @Test
    fun `the setting naming Maia settles it whatever the role query said`() {
        // Nothing else can be in that setting while Maia is bound to it, so a
        // failed or unavailable role query does not make a working assistant
        // look broken.
        assertEquals(
            AssistantBinding.Bound,
            assistantBinding(roleHeld = null, namesMaia = true),
        )
        assertEquals(
            AssistantBinding.Bound,
            assistantBinding(roleHeld = false, namesMaia = true),
        )
    }
}
