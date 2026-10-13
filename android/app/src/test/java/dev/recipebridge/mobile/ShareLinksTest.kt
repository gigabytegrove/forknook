package dev.recipebridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinksTest {
    @Test fun instagramReelFromCaption() {
        assertEquals(
            "https://www.instagram.com/reel/Cd123_456/?igsh=abc123",
            firstSharedUrl("Watch this recipe https://www.instagram.com/reel/Cd123_456/?igsh=abc123")
        )
    }

    @Test fun trimSentencePunctuation() {
        assertEquals(
            "https://www.instagram.com/p/Cd123456/",
            firstSharedUrl("Found dinner! https://www.instagram.com/p/Cd123456/).")
        )
    }

    @Test fun facebookVideoShareLink() {
        assertEquals(
            "https://www.facebook.com/reel/123456789",
            firstSharedUrl("Check out https://www.facebook.com/reel/123456789")
        )
    }

    @Test fun plainRecipeTextHasNoUrl() {
        assertNull(firstSharedUrl("2 tablespoons sesame oil, chicken, and ginger"))
    }
}
