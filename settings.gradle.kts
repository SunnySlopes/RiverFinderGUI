rootProject.name = "RiverFinder"

// Native search core (JNI branch). RiverFinderBase owns its cubiomes nested
// submodule; the main module must not add a top-level cubiomes submodule.
include("riverfinder")
project(":riverfinder").projectDir = file("RiverFinderBase")
