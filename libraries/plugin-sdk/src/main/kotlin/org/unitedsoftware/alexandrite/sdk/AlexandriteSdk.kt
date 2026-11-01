package org.unitedsoftware.alexandrite.sdk

/**
 * Constants of the plugin SDK, which follows semantic versioning from 1.0.
 *
 * How the SDK grows without breaking the plugins built against it:
 * - a type with a builder (`builder(…)`, `toBuilder()`, `rebuild { }`) gains optional properties;
 * - an interface marked `@SubclassOptInRequired(InternalAlexandriteApi::class)` is implemented by Alexandrite alone
 *   and gains members and subtypes, so a `when` over its subtypes needs an `else` branch;
 * - an open enumeration, a value class with companion constants, gains constants, so a `when` over it needs an `else`
 *   branch;
 * - an enum whose values only plugins produce may gain constants, so a `when` over it needs an `else` branch too;
 * - a value that Alexandrite alone produces, whose constructor is marked `@InternalAlexandriteApi`, gains properties,
 *   and a plugin's tests build it with the testkit;
 * - an interface that plugins implement gains members only with default bodies;
 * - the stored JSON of transcript entries gains types and fields, and a version reads a type it does not know as its
 *   `Unknown` form;
 * - every other change, a new parameter of a public constructor included, breaks plugins and raises [API_VERSION].
 */
public object AlexandriteSdk {
    /**
     * The SDK's major version, 1 for its first release and raised by every breaking change.
     *
     * A runtime loads only plugins built against its own.
     */
    public const val API_VERSION: Int = 2
}
