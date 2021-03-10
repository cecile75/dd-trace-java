package datadog.trace.bootstrap.instrumentation.ci

class CircleCIInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new CircleCIInfo() {
      @Override
      protected String getGitFolderName() {
        return GIT_FOLDER_FOR_TESTS
      }
    }
  }

  @Override
  String getProviderName() {
    return CircleCIInfo.CIRCLECI_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(CircleCIInfo.CIRCLECI, "true")
    map.put(CircleCIInfo.CIRCLECI_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(CircleCIInfo.CIRCLECI, "true")
    map.put(CircleCIInfo.CIRCLECI_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(CircleCIInfo.CIRCLECI_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
