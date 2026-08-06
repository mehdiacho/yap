package com.mehdiacho.yap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  /**
   * Smoke test: Robolectric boots and resources resolve.
   *
   * It used to assert the literal "My Application" from the project template, which made it
   * fail the moment the debug variant was renamed "Yap Dev" — a red suite for a rename that
   * broke nothing. The prefix is what's actually invariant across variants.
   */
  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertTrue("unexpected app name: $appName", appName.startsWith("Yap"))
  }
}
