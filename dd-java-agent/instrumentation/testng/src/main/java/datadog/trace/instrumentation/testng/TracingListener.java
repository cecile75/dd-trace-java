package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.testng.retry.RetryAnalyzer;
import java.lang.reflect.Method;
import java.util.List;
import org.testng.IConfigurationListener;
import org.testng.IRetryAnalyzer;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener extends TestNGClassListener
    implements ITestListener, IConfigurationListener {

  public static final String FRAMEWORK_NAME = "testng";
  public static final String FRAMEWORK_VERSION = TestNGUtils.getTestNGVersion();

  @Override
  public void onStart(final ITestContext context) {
    // ignore
  }

  @Override
  public void onFinish(final ITestContext context) {
    // ignore
  }

  @Override
  protected void onBeforeClass(ITestClass testClass, boolean parallelized) {
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    List<String> groups = TestNGUtils.getGroups(testClass);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testSuiteClass,
        groups,
        parallelized,
        TestFrameworkInstrumentation.TESTNG);
  }

  @Override
  protected void onAfterClass(ITestClass testClass) {
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testSuiteClass);
  }

  @Override
  public void onConfigurationSuccess(ITestResult result) {
    // ignore
  }

  @Override
  public void onConfigurationFailure(ITestResult result) {
    // suite setup or suite teardown failed
    String testSuiteName = result.getInstanceName();
    Class<?> testClass = TestNGUtils.getTestClass(result);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
        testSuiteName, testClass, result.getThrowable());
  }

  @Override
  public void onConfigurationSkip(ITestResult result) {
    // ignore
  }

  @Override
  public void onTestStart(final ITestResult result) {
    String testSuiteName = result.getInstanceName();
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);
    List<String> groups = TestNGUtils.getGroups(result);

    Class<?> testClass = TestNGUtils.getTestClass(result);
    Method testMethod = TestNGUtils.getTestMethod(result);
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuiteName,
        testName,
        result,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testParameters,
        groups,
        testClass,
        testMethodName,
        testMethod,
        isRetry(result));
  }

  private boolean isRetry(final ITestResult result) {
    IRetryAnalyzer retryAnalyzer = TestNGUtils.getRetryAnalyzer(result);
    if (retryAnalyzer instanceof RetryAnalyzer) {
      RetryAnalyzer datadogAnalyzer = (RetryAnalyzer) retryAnalyzer;
      return datadogAnalyzer.currentExecutionIsRetry();
    }
    return false;
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, result, testParameters);
  }

  @Override
  public void onTestFailure(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);

    final Throwable throwable = result.getThrowable();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
        testSuiteName, testClass, testName, result, testParameters, throwable);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, result, testParameters);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(final ITestResult result) {
    onTestFailure(result);
  }

  @Override
  public void onTestSkipped(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);

    Throwable throwable = result.getThrowable();
    if (TestNGUtils.wasRetried(result)) {
      // TestNG reports tests retried with IRetryAnalyzer as skipped,
      // this is done to avoid failing the build when retrying tests.
      // We want to report such tests as failed to Datadog,
      // to provide more accurate data (and to enable flakiness detection)
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
          testSuiteName, testClass, testName, result, testParameters, throwable);
    } else {
      // Typically the way of skipping a TestNG test is throwing a SkipException
      String reason = throwable != null ? throwable.getMessage() : null;
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
          testSuiteName, testClass, testName, result, testParameters, reason);
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, result, testParameters);
  }
}
