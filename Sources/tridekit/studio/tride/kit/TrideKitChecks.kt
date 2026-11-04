/**
 * `TrideKitChecks` — marks calls that exist for checks of TrideKit itself (the parity gate, the test app,
 * tests), not for apps: an app that calls one opts in and accepts that it may change or go.
 */
package studio.tride.kit

@RequiresOptIn(message = "For checks of TrideKit itself (the parity gate, tests); not a stable API.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class TrideKitChecks
