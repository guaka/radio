# Contributor guidance

- Cover new features and bug fixes with automated tests that exercise the changed behavior. For a bug fix, add a regression test that fails without the fix when practical.
- Run the relevant tests before finishing and report any checks that could not be run. Android changes should pass `./gradlew testDebugUnitTest lintDebug` from `android/`; add instrumentation/Compose tests for UI behavior where appropriate.
