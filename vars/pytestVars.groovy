class pytestVars implements Serializable {
    // Pytest exit codes
    // https://docs.pytest.org/en/stable/reference.html#pytest.ExitCode
    final int EXIT_OK              = 0
    final int EXIT_TESTS_FAILED    = 1
    final int EXIT_INTRRUPTED      = 2
    final int EXIT_INTERNAL_ERROR  = 3
    final int EXIT_USAGE_ERROR     = 4
    final int EXIT_NO_TESTS        = 5

    // Minimum version of pytest capable of emitting reliable exit codes
    final String EXIT_CAPABLE = "5.0"
}

