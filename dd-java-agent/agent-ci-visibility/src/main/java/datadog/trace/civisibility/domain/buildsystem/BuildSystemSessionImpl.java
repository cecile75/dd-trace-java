package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.coverage.CoverageUtils;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.domain.BuildSystemSession;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.ModuleSettingsRequest;
import datadog.trace.civisibility.ipc.ModuleSettingsResponse;
import datadog.trace.civisibility.ipc.RepoIndexRequest;
import datadog.trace.civisibility.ipc.RepoIndexResponse;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.utils.SpanUtils;
import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

public class BuildSystemSessionImpl extends AbstractTestSession implements BuildSystemSession {
  private final String repoRoot;
  private final String startCommand;
  private final TestModuleRegistry testModuleRegistry;
  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;
  private final SignalServer signalServer;
  private final RepoIndexProvider repoIndexProvider;
  protected final LongAdder testsSkipped = new LongAdder();
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;
  private final Object coverageDataLock = new Object();

  @GuardedBy("coverageDataLock")
  private final ExecutionDataStore coverageData = new ExecutionDataStore();

  @GuardedBy("coverageDataLock")
  private final Collection<File> outputClassesDirs = new HashSet<>();

  public BuildSystemSessionImpl(
      String projectName,
      String repoRoot,
      String startCommand,
      @Nullable Long startTime,
      boolean supportedCiProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestModuleRegistry testModuleRegistry,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      SignalServer signalServer,
      RepoIndexProvider repoIndexProvider) {
    super(
        projectName,
        startTime,
        InstrumentationType.BUILD,
        supportedCiProvider,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory);
    this.repoRoot = repoRoot;
    this.startCommand = startCommand;
    this.testModuleRegistry = testModuleRegistry;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.signalServer = signalServer;
    this.repoIndexProvider = repoIndexProvider;

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, this::onModuleExecutionResultReceived);
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.MODULE_SETTINGS_REQUEST, this::onModuleExecutionSettingsRequestReceived);
    signalServer.start();

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  private SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isItrEnabled()) {
      itrEnabled = true;
    }
    if (result.isEarlyFlakeDetectionEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (result.isEarlyFlakeDetectionFaulty()) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, "faulty");
      }
    }

    testsSkipped.add(result.getTestsSkippedTotal());

    ExecutionDataStore moduleCoverageData = CoverageUtils.parse(result.getCoverageData());
    if (moduleCoverageData != null) {
      synchronized (coverageDataLock) {
        // add module coverage data to session coverage data
        moduleCoverageData.accept(coverageData);
      }
    }

    return testModuleRegistry.onModuleExecutionResultReceived(result);
  }

  private SignalResponse onRepoIndexRequestReceived(RepoIndexRequest request) {
    try {
      RepoIndex index = repoIndexProvider.getIndex();
      return new RepoIndexResponse(index);
    } catch (Exception e) {
      return new ErrorResponse("Error while building repo index: " + e.getMessage());
    }
  }

  private SignalResponse onModuleExecutionSettingsRequestReceived(ModuleSettingsRequest request) {
    try {
      JvmInfo jvmInfo = request.getJvmInfo();
      ModuleExecutionSettings settings = getModuleExecutionSettings(jvmInfo);

      String moduleName = request.getModuleName();
      Collection<TestIdentifier> skippableTestsForModule = settings.getSkippableTests(moduleName);
      Map<String, Collection<TestIdentifier>> skippableTests =
          !skippableTestsForModule.isEmpty()
              ? Collections.singletonMap(moduleName, skippableTestsForModule)
              : Collections.emptyMap();

      Collection<TestIdentifier> knownTestsForModule = settings.getKnownTests(moduleName);
      Map<String, Collection<TestIdentifier>> knownTests =
          knownTestsForModule != null
              ? Collections.singletonMap(moduleName, knownTestsForModule)
              : null;

      ModuleExecutionSettings moduleSettings =
          new ModuleExecutionSettings(
              settings.isCodeCoverageEnabled(),
              settings.isItrEnabled(),
              settings.isFlakyTestRetriesEnabled(),
              settings.getEarlyFlakeDetectionSettings(),
              settings.getSystemProperties(),
              settings.getItrCorrelationId(),
              skippableTests,
              settings.getFlakyTests(moduleName),
              knownTests,
              settings.getCoverageEnabledPackages());

      return new ModuleSettingsResponse(moduleSettings);
    } catch (Exception e) {
      return new ErrorResponse("Error while getting module execution settings: " + e.getMessage());
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    signalServer.stop();

    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (itrEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    synchronized (coverageDataLock) {
      if (!coverageData.getContents().isEmpty()) {
        processCoverageData(coverageData);
      }
    }

    super.end(endTime);
  }

  private void processCoverageData(ExecutionDataStore coverageData) {
    IBundleCoverage coverageBundle =
        CoverageUtils.createCoverageBundle(coverageData, outputClassesDirs);
    if (coverageBundle == null) {
      return;
    }

    long coveragePercentage = getCoveragePercentage(coverageBundle);
    setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);

    File coverageReportFolder = getCoverageReportFolder();
    if (coverageReportFolder != null) {
      CoverageUtils.dumpCoverageReport(
          coverageBundle, repoIndexProvider.getIndex(), repoRoot, coverageReportFolder);
    }
  }

  private static long getCoveragePercentage(IBundleCoverage coverageBundle) {
    ICounter instructionCounter = coverageBundle.getInstructionCounter();
    int totalInstructionsCount = instructionCounter.getTotalCount();
    int coveredInstructionsCount = instructionCounter.getCoveredCount();
    return Math.round((100d * coveredInstructionsCount) / totalInstructionsCount);
  }

  private File getCoverageReportFolder() {
    String coverageReportDumpDir = config.getCiVisibilityCodeCoverageReportDumpDir();
    if (coverageReportDumpDir != null) {
      return Paths.get(coverageReportDumpDir, "session-" + span.getSpanId(), "aggregated")
          .toAbsolutePath()
          .toFile();
    } else {
      return null;
    }
  }

  @Override
  public BuildSystemModuleImpl testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs) {
    synchronized (coverageDataLock) {
      this.outputClassesDirs.addAll(outputClassesDirs);
    }

    BuildSystemModuleImpl module =
        new BuildSystemModuleImpl(
            span.context(),
            span.getSpanId(),
            moduleName,
            repoRoot,
            startCommand,
            startTime,
            signalServer.getAddress(),
            outputClassesDirs,
            config,
            metricCollector,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            coverageProbeStoreFactory,
            repoIndexProvider,
            testModuleRegistry,
            SpanUtils.propagateCiVisibilityTagsTo(span));
    testModuleRegistry.addModule(module);
    return module;
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo) {
    return moduleExecutionSettingsFactory.create(jvmInfo, null);
  }
}
