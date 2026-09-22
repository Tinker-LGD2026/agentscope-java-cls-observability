The binary compatibility fixture is generated at test runtime by V02BinaryCompatibilityTest.

The test compiles the auditable v0-2-source/V02Client.java against minimal 0.2 API stubs in a temporary directory, removes the generated stub classes, and then loads the precompiled client bytecode against the current SDK classes. No binary fixture is committed to the public repository.
