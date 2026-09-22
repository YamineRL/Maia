package dev.maia.nlu.assistant

import dev.maia.nlu.Intent
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class WeatherAskTest {

    private fun query(text: String) = WeatherAsk.of(text)?.target

    @Test
    fun `the place named after the weather word is the one looked up`() {
        assertEquals("weather in Paris", query("what's the weather in Paris"))
        assertEquals("weather in New York tomorrow", query("what's the weather like in new york tomorrow"))
        assertEquals("weather in Lyon", query("use the devbox to check the weather in Lyon"))
        assertEquals("weather in Aix-en-provence this weekend", query("is it going to rain in aix-en-provence this weekend?"))
        assertEquals("weather in London", query("forecast for London please"))
    }

    @Test
    fun `no place leaves the place to the search`() {
        assertEquals("weather", query("do I need an umbrella"))
        assertEquals("weather tomorrow", query("what's the forecast for tomorrow"))
        assertEquals("weather", query("what is the weather in the morning"))
        assertEquals("weather", query("use the devbox in the office to check the weather"))
    }

    @Test
    fun `the home city fills in only when no place is said`() {
        assertEquals("weather in Montréal", WeatherAsk.of("what's the weather", home = " Montréal ")?.target)
        assertEquals("weather in Montréal tomorrow", WeatherAsk.of("do I need an umbrella tomorrow", home = "Montréal")?.target)
        assertEquals("weather in Paris", WeatherAsk.of("weather in paris", home = "Montréal")?.target)
        assertEquals("weather", WeatherAsk.of("what's the weather", home = "  ")?.target)
    }

    @Test
    fun `the transcript rides along verbatim`() {
        assertEquals(Intent.OpenWeb("weather in Oslo", "Weather in Oslo"), WeatherAsk.of("Weather in Oslo"))
    }

    @Test
    fun `a question about something else is not weather`() {
        assertNull(WeatherAsk.of("which football games are on today"))
        assertNull(WeatherAsk.of("what is the capital of Peru"))
        assertNull(WeatherAsk.of("tell me about the brain"))
    }
}
