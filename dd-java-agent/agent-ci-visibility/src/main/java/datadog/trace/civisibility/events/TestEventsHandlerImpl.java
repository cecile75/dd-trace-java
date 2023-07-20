package datadog.trace.civisibility.events;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.DDTestImpl;
import datadog.trace.civisibility.DDTestModuleChild;
import datadog.trace.civisibility.DDTestModuleImpl;
import datadog.trace.civisibility.DDTestModuleParent;
import datadog.trace.civisibility.DDTestSuiteImpl;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.EmptyTestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandlerImpl implements TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private final String moduleName;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;

  private volatile DDTestModuleImpl testModule;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, DDTestSuiteImpl> inProgressTestSuites =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestDescriptor, DDTestImpl> inProgressTests =
      new ConcurrentHashMap<>();

  private volatile boolean testsSkipped;

  public TestEventsHandlerImpl(
      String moduleName,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver) {
    this.moduleName = moduleName;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
  }

  @Override
  public void onTestModuleStart() {
    getTestModule();
  }

  private DDTestModuleImpl getTestModule() {
    if (testModule == null) {
      synchronized (this) {
        if (testModule == null) {
          testModule = createTestModule();
        }
      }
    }
    return testModule;
  }

  private DDTestModuleImpl createTestModule() {
    // fallbacks to System.getProperty below are needed for cases when
    // system variables are set after config was initialized

    Long parentProcessSessionId = config.getCiVisibilitySessionId();
    if (parentProcessSessionId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      if (systemProp != null) {
        parentProcessSessionId = Long.parseLong(systemProp);
      }
    }

    Long parentProcessModuleId = config.getCiVisibilityModuleId();
    if (parentProcessModuleId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
      if (systemProp != null) {
        parentProcessModuleId = Long.parseLong(systemProp);
      }
    }

    if (parentProcessSessionId == null || parentProcessModuleId == null) {
      // it is likely that parent process (build system) is not instrumented
      // since session and module IDs are not provided to us
      return new DDTestModuleParent(
          null,
          moduleName,
          null,
          config,
          null,
          testDecorator,
          sourcePathResolver,
          codeowners,
          methodLinesResolver,
          null);
    }

    InetSocketAddress signalServerAddress = null;
    String host =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST));
    String port =
        System.getProperty(
            Strings.propertyNameToSystemPropertyName(
                CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT));
    if (host != null && port != null) {
      signalServerAddress = new InetSocketAddress(host, Integer.parseInt(port));
    }

    return new DDTestModuleChild(
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        signalServerAddress);
  }

  @Override
  public void onTestModuleFinish() {
    if (testModule != null) {
      synchronized (this) {
        if (testModule != null) {
          testModule.end(null, testsSkipped);
          testModule = null;
        }
      }
    }
  }

  @Override
  public void onTestSuiteStart(
      final String testSuiteName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable Class<?> testClass,
      final @Nullable Collection<String> categories,
      boolean parallelized) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    if (!tryTestSuiteStart(descriptor)) {
      return;
    }

    DDTestSuiteImpl testSuite =
        getTestModule().testSuiteStart(testSuiteName, testClass, null, parallelized);

    if (testFramework != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    if (testFrameworkVersion != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }
    if (categories != null && !categories.isEmpty()) {
      testSuite.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
    }

    inProgressTestSuites.put(descriptor, testSuite);
  }

  @Override
  public void onTestSuiteFinish(final String testSuiteName, final @Nullable Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    if (!tryTestSuiteFinish(descriptor)) {
      return;
    }

    DDTestSuiteImpl testSuite = inProgressTestSuites.remove(descriptor);
    testSuite.end(null);
  }

  private boolean tryTestSuiteStart(TestSuiteDescriptor descriptor) {
    return testSuiteNestedCallCounters.merge(descriptor, 1, Integer::sum) == 1;
  }

  private boolean tryTestSuiteFinish(TestSuiteDescriptor descriptor) {
    return testSuiteNestedCallCounters.merge(descriptor, -1, (a, b) -> a + b > 0 ? a + b : null)
        == null;
  }

  @Override
  public void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug(
          "Ignoring skip event, could not find test suite with name {} and class {}",
          testSuiteName,
          testClass);
      return;
    }
    testSuite.setSkipReason(reason);
  }

  @Override
  public void onTestSuiteFailure(
      String testSuiteName, Class<?> testClass, @Nullable Throwable throwable) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug(
          "Ignoring fail event, could not find test suite with name {} and class {}",
          testSuiteName,
          testClass);
      return;
    }
    testSuite.setErrorInfo(throwable);
  }

  @Override
  public void onTestStart(
      final String testSuiteName,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable String testMethodName,
      final @Nullable Method testMethod) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor suiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(suiteDescriptor);
    DDTestImpl test =
        testSuite != null
            ? testSuite.testStart(testName, testMethodName, testMethod, null)
            // suite events are not reported in Cucumber / JUnit 4 combination
            : new DDTestImpl(
                EmptyTestContext.INSTANCE,
                EmptyTestContext.INSTANCE,
                null,
                testSuiteName,
                testName,
                null,
                testClass,
                testMethodName,
                testMethod,
                config,
                testDecorator,
                sourcePathResolver,
                methodLinesResolver,
                codeowners);

    if (testFramework != null) {
      test.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    if (testFrameworkVersion != null) {
      test.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }
    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (categories != null && !categories.isEmpty()) {
      String json = toJson(Collections.singletonMap("category", toJson(categories)), true);
      test.setTag(Tags.TEST_TRAITS, json);
    }

    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    inProgressTests.put(descriptor, test);
  }

  @Override
  public void onTestSkip(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable String reason) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring skip event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.setSkipReason(reason);
  }

  @Override
  public void onTestFailure(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable Throwable throwable) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring fail event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.setErrorInfo(throwable);
  }

  @Override
  public void onTestFinish(
      final String testSuiteName,
      final Class<?> testClass,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testParameters) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.remove(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring finish event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.end(null);
  }

  @Override
  public void onTestIgnore(
      final String testSuiteName,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable List<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable String testMethodName,
      final @Nullable Method testMethod,
      final @Nullable String reason) {
    onTestStart(
        testSuiteName,
        testName,
        testQualifier,
        testFramework,
        testFrameworkVersion,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod);
    onTestSkip(testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  @Override
  public boolean skip(SkippableTest test) {
    Set<SkippableTest> skippableTests = Config.get().getCiVisibilitySkippableTests();
    if (skippableTests.contains(test)) {
      testsSkipped = true;
      return true;
    } else {
      return false;
    }
  }
}
