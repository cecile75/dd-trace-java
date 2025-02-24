package datadog.trace.civisibility.events;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.domain.TestImpl;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.utils.ConcurrentHashMapContextStore;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandlerImpl implements TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private final CiVisibilityMetricCollector metricCollector;
  private final TestFrameworkSession testSession;
  private final TestFrameworkModule testModule;

  private final ContextStore<TestSuiteDescriptor, TestSuiteImpl> inProgressTestSuites =
      new ConcurrentHashMapContextStore<>();

  private final ContextStore<TestDescriptor, TestImpl> inProgressTests =
      new ConcurrentHashMapContextStore<>();

  public TestEventsHandlerImpl(
      CiVisibilityMetricCollector metricCollector,
      TestFrameworkSession testSession,
      TestFrameworkModule testModule) {
    this.metricCollector = metricCollector;
    this.testSession = testSession;
    this.testModule = testModule;
  }

  @Override
  public void onTestSuiteStart(
      final String testSuiteName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable Class<?> testClass,
      final @Nullable Collection<String> categories,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteImpl testSuite =
        testModule.testSuiteStart(testSuiteName, testClass, null, parallelized, instrumentation);

    if (testFramework != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK, testFramework);
      if (testFrameworkVersion != null) {
        testSuite.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
      }
    }
    if (categories != null && !categories.isEmpty()) {
      testSuite.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    inProgressTestSuites.put(descriptor, testSuite);
  }

  @Override
  public void onTestSuiteFinish(final String testSuiteName, final @Nullable Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestSuiteImpl testSuite = inProgressTestSuites.remove(descriptor);
    testSuite.end(null);
  }

  @Override
  public void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
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
    TestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
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
      final @Nullable Method testMethod,
      final boolean isRetry) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor suiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestSuiteImpl testSuite = inProgressTestSuites.get(suiteDescriptor);
    TestImpl test = testSuite.testStart(testName, testMethod, null);

    TestIdentifier thisTest = new TestIdentifier(testSuiteName, testName, testParameters, null);
    if (testModule.isNew(thisTest)) {
      test.setTag(Tags.TEST_IS_NEW, true);
    }

    if (testFramework != null) {
      test.setTag(Tags.TEST_FRAMEWORK, testFramework);
      if (testFrameworkVersion != null) {
        test.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
      }
    }
    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (testMethodName != null && testMethod != null) {
      test.setTag(Tags.TEST_SOURCE_METHOD, testMethodName + Type.getMethodDescriptor(testMethod));
    }
    if (categories != null && !categories.isEmpty()) {
      String json = toJson(Collections.singletonMap("category", toJson(categories)), true);
      test.setTag(Tags.TEST_TRAITS, json);

      for (String category : categories) {
        if (category.endsWith(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)) {
          test.setTag(Tags.TEST_ITR_UNSKIPPABLE, true);
          metricCollector.add(CiVisibilityCountMetric.ITR_UNSKIPPABLE, 1, EventType.TEST);

          if (testModule.isSkippable(thisTest)) {
            test.setTag(Tags.TEST_ITR_FORCED_RUN, true);
            metricCollector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 1, EventType.TEST);
          }
          break;
        }
      }
    }

    if (isRetry) {
      test.setTag(Tags.TEST_IS_RETRY, true);
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
    TestImpl test = inProgressTests.get(descriptor);
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
    TestImpl test = inProgressTests.get(descriptor);
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
    TestImpl test = inProgressTests.remove(descriptor);
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
      final @Nullable Collection<String> categories,
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
        testMethod,
        false);
    onTestSkip(testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  @Override
  public boolean skip(TestIdentifier test) {
    return testModule.skip(test);
  }

  @Override
  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    return testModule.retryPolicy(test);
  }

  @Override
  public void close() {
    testModule.end(null);
    testSession.end(null);
  }
}
