/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sqoop.framework;

import org.apache.log4j.Logger;
import org.apache.sqoop.common.MapContext;
import org.apache.sqoop.common.SqoopException;
import org.apache.sqoop.connector.ConnectorManager;
import org.apache.sqoop.connector.spi.SqoopConnector;
import org.apache.sqoop.core.SqoopConfiguration;
import org.apache.sqoop.framework.configuration.ConnectionConfiguration;
import org.apache.sqoop.framework.configuration.ExportJobConfiguration;
import org.apache.sqoop.framework.configuration.ImportJobConfiguration;
import org.apache.sqoop.job.etl.CallbackBase;
import org.apache.sqoop.job.etl.Destroyer;
import org.apache.sqoop.job.etl.Initializer;
import org.apache.sqoop.model.FormUtils;
import org.apache.sqoop.model.MConnection;
import org.apache.sqoop.model.MConnectionForms;
import org.apache.sqoop.model.MJob;
import org.apache.sqoop.model.MFramework;
import org.apache.sqoop.model.MJobForms;
import org.apache.sqoop.model.MSubmission;
import org.apache.sqoop.repository.Repository;
import org.apache.sqoop.repository.RepositoryManager;
import org.apache.sqoop.submission.SubmissionStatus;
import org.apache.sqoop.submission.counter.Counters;
import org.apache.sqoop.utils.ClassUtils;
import org.apache.sqoop.validation.Validator;
import org.json.simple.JSONValue;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Manager for Sqoop framework itself.
 *
 * All Sqoop internals are handled in this class:
 * * Submission engine
 * * Execution engine
 * * Framework metadata
 *
 * Current implementation of entire submission engine is using repository
 * for keeping track of running submissions. Thus, server might be restarted at
 * any time without any affect on running jobs. This approach however might not
 * be the fastest way and we might want to introduce internal structures for
 * running jobs in case that this approach will be too slow.
 */
public final class FrameworkManager {

  private static final Logger LOG = Logger.getLogger(FrameworkManager.class);

  /**
   * Default interval for purging old submissions from repository.
   */
  private static final long DEFAULT_PURGE_THRESHOLD = 24*60*60*1000;

  /**
   * Default sleep interval for purge thread.
   */
  private static final long DEFAULT_PURGE_SLEEP = 24*60*60*1000;

  /**
   * Default interval for update thread.
   */
  private static final long DEFAULT_UPDATE_SLEEP = 60*5*1000;

  /**
   * Framework metadata structures in MForm format
   */
  private static MFramework mFramework;

  /**
   * Validator instance
   */
  private static final Validator validator;

  /**
   * Configured submission engine instance
   */
  private static SubmissionEngine submissionEngine;

  /**
   * Configured execution engine instance
   */
  private static ExecutionEngine executionEngine;

  /**
   * Purge thread that will periodically remove old submissions from repository.
   */
  private static PurgeThread purgeThread = null;

  /**
   * Update thread that will periodically check status of running submissions.
   */
  private static UpdateThread updateThread = null;

  /**
   * Synchronization variable between threads.
   */
  private static boolean running = true;

  /**
   * Specifies how old submissions should be removed from repository.
   */
  private static long purgeThreshold;

  /**
   * Number of milliseconds for purge thread to sleep.
   */
  private static long purgeSleep;

  /**
   * Number of milliseconds for update thread to slepp.
   */
  private static long updateSleep;

  /**
   * Mutex for creating new submissions. We're not allowing more then one
   * running submission for one job.
   */
  private static final Object submissionMutex = new Object();

  static {
    MConnectionForms connectionForms = new MConnectionForms(
      FormUtils.toForms(getConnectionConfigurationClass())
    );
    List<MJobForms> jobForms = new LinkedList<MJobForms>();
    jobForms.add(new MJobForms(MJob.Type.IMPORT,
      FormUtils.toForms(getJobConfigurationClass(MJob.Type.IMPORT))));
    jobForms.add(new MJobForms(MJob.Type.EXPORT,
      FormUtils.toForms(getJobConfigurationClass(MJob.Type.EXPORT))));
    mFramework = new MFramework(connectionForms, jobForms);

    // Build validator
    validator = new Validator();
  }

  public static synchronized void initialize() {
    LOG.trace("Begin submission engine manager initialization");
    MapContext context = SqoopConfiguration.getContext();

    // Register framework metadata in repository
    mFramework = RepositoryManager.getRepository().registerFramework(mFramework);

    // Let's load configured submission engine
    String submissionEngineClassName =
      context.getString(FrameworkConstants.SYSCFG_SUBMISSION_ENGINE);

    submissionEngine = (SubmissionEngine) ClassUtils.instantiate(submissionEngineClassName);
    if(submissionEngine == null) {
      throw new SqoopException(FrameworkError.FRAMEWORK_0001,
        submissionEngineClassName);
    }

    submissionEngine.initialize(context, FrameworkConstants.PREFIX_SUBMISSION_ENGINE_CONFIG);

    // Execution engine
    String executionEngineClassName =
      context.getString(FrameworkConstants.SYSCFG_EXECUTION_ENGINE);

    executionEngine = (ExecutionEngine) ClassUtils.instantiate(executionEngineClassName);
    if(executionEngine == null) {
      throw new SqoopException(FrameworkError.FRAMEWORK_0007,
        executionEngineClassName);
    }

    // We need to make sure that user has configured compatible combination of
    // submission engine and execution engine
    if(! submissionEngine.isExecutionEngineSupported(executionEngine.getClass())) {
      throw new SqoopException(FrameworkError.FRAMEWORK_0008);
    }

    executionEngine.initialize(context, FrameworkConstants.PREFIX_EXECUTION_ENGINE_CONFIG);

    // Set up worker threads
    purgeThreshold = context.getLong(
      FrameworkConstants.SYSCFG_SUBMISSION_PURGE_THRESHOLD,
      DEFAULT_PURGE_THRESHOLD
    );
    purgeSleep = context.getLong(
      FrameworkConstants.SYSCFG_SUBMISSION_PURGE_SLEEP,
      DEFAULT_PURGE_SLEEP
    );

    purgeThread = new PurgeThread();
    purgeThread.start();

    updateSleep = context.getLong(
      FrameworkConstants.SYSCFG_SUBMISSION_UPDATE_SLEEP,
      DEFAULT_UPDATE_SLEEP
    );

    updateThread = new UpdateThread();
    updateThread.start();

    LOG.info("Submission manager initialized: OK");
  }

  public static synchronized void destroy() {
    LOG.trace("Begin submission engine manager destroy");

    running = false;

    try {
      purgeThread.interrupt();
      purgeThread.join();
    } catch (InterruptedException e) {
      //TODO(jarcec): Do I want to wait until it actually finish here?
      LOG.error("Interrupted joining purgeThread");
    }

    try {
      updateThread.interrupt();
      updateThread.join();
    } catch (InterruptedException e) {
      //TODO(jarcec): Do I want to wait until it actually finish here?
      LOG.error("Interrupted joining updateThread");
    }

    if(submissionEngine != null) {
      submissionEngine.destroy();
    }

    if(executionEngine != null) {
      executionEngine.destroy();
    }
  }

  public static Validator getValidator() {
    return validator;
  }

  public static Class getConnectionConfigurationClass() {
    return ConnectionConfiguration.class;
  }

  public static Class getJobConfigurationClass(MJob.Type jobType) {
    switch (jobType) {
      case IMPORT:
        return ImportJobConfiguration.class;
      case EXPORT:
        return ExportJobConfiguration.class;
      default:
        return null;
    }
  }

  public static MFramework getFramework() {
    return mFramework;
  }

  public static ResourceBundle getBundle(Locale locale) {
    return ResourceBundle.getBundle(
        FrameworkConstants.RESOURCE_BUNDLE_NAME, locale);
  }

  public static MSubmission submit(long jobId) {
    Repository repository = RepositoryManager.getRepository();

    MJob job = repository.findJob(jobId);
    if(job == null) {
      throw new SqoopException(FrameworkError.FRAMEWORK_0004,
        "Unknown job id " + jobId);
    }
    MConnection connection = repository.findConnection(job.getConnectionId());
    SqoopConnector connector =
      ConnectorManager.getConnector(job.getConnectorId());

    // Transform forms to connector specific classes
    Object connectorConnection = ClassUtils.instantiate(
      connector.getConnectionConfigurationClass());
    FormUtils.fromForms(connection.getConnectorPart().getForms(),
      connectorConnection);

    Object connectorJob = ClassUtils.instantiate(
      connector.getJobConfigurationClass(job.getType()));
    FormUtils.fromForms(job.getConnectorPart().getForms(), connectorJob);

    // Transform framework specific forms
    Object frameworkConnection = ClassUtils.instantiate(
      getConnectionConfigurationClass());
    FormUtils.fromForms(connection.getFrameworkPart().getForms(),
      frameworkConnection);

    Object frameworkJob = ClassUtils.instantiate(
      getJobConfigurationClass(job.getType()));
    FormUtils.fromForms(job.getFrameworkPart().getForms(), frameworkJob);

    // Create request object
    MSubmission summary = new MSubmission(jobId);
    SubmissionRequest request = executionEngine.createSubmissionRequest();

    // Save important variables to the submission request
    request.setSummary(summary);
    request.setConnector(connector);
    request.setConfigConnectorConnection(connectorConnection);
    request.setConfigConnectorJob(connectorJob);
    request.setConfigFrameworkConnection(frameworkConnection);
    request.setConfigFrameworkJob(frameworkJob);
    request.setJobType(job.getType());
    request.setJobName(job.getName());
    request.setJobId(job.getPersistenceId());

    // Let's register all important jars
    // sqoop-common
    request.addJarForClass(MapContext.class);
    // sqoop-core
    request.addJarForClass(FrameworkManager.class);
    // sqoop-spi
    request.addJarForClass(SqoopConnector.class);
    // Execution engine jar
    request.addJarForClass(executionEngine.getClass());
    // Connector in use
    request.addJarForClass(connector.getClass());

    // Extra libraries that Sqoop code requires
    request.addJarForClass(JSONValue.class);

    // Get connector callbacks
    switch (job.getType()) {
      case IMPORT:
        request.setConnectorCallbacks(connector.getImporter());
        break;
      case EXPORT:
        request.setConnectorCallbacks(connector.getExporter());
        break;
      default:
        throw  new SqoopException(FrameworkError.FRAMEWORK_0005,
          "Unsupported job type " + job.getType().name());
    }
    LOG.debug("Using callbacks: " + request.getConnectorCallbacks());

    // Initialize submission from connector perspective
    CallbackBase baseCallbacks = request.getConnectorCallbacks();

    Class<? extends Initializer> initializerClass = baseCallbacks.getInitializer();
    Initializer initializer = (Initializer) ClassUtils.instantiate(initializerClass);

    if(initializer == null) {
      throw  new SqoopException(FrameworkError.FRAMEWORK_0006,
        "Can't create initializer instance: " + initializerClass.getName());
    }

    // Initialize submission from connector perspective
    initializer.initialize(request.getConnectorContext(),
      request.getConfigConnectorConnection(),
      request.getConfigConnectorJob());

    // Add job specific jars to
    request.addJars(initializer.getJars(request.getConnectorContext(),
      request.getConfigConnectorConnection(),
      request.getConfigConnectorJob()));

    // Bootstrap job from framework perspective
    switch (job.getType()) {
      case IMPORT:
        prepareImportSubmission(request);
        break;
      case EXPORT:
        // TODO(jarcec): Implement export path
        break;
      default:
        throw  new SqoopException(FrameworkError.FRAMEWORK_0005,
          "Unsupported job type " + job.getType().name());
    }

    // Make sure that this job id is not currently running and submit the job
    // only if it's not.
    synchronized (submissionMutex) {
      MSubmission lastSubmission = repository.findSubmissionLastForJob(jobId);
      if(lastSubmission != null && lastSubmission.getStatus().isRunning()) {
        throw new SqoopException(FrameworkError.FRAMEWORK_0002,
          "Job with id " + jobId);
      }

      // TODO(jarcec): We might need to catch all exceptions here to ensure
      // that Destroyer will be executed in all cases.
      boolean submitted = submissionEngine.submit(request);
      if(!submitted) {
        destroySubmission(request);
        summary.setStatus(SubmissionStatus.FAILURE_ON_SUBMIT);
      }

      repository.createSubmission(summary);
    }

    // Return job status most recent
    return summary;
  }

  private static void prepareImportSubmission(SubmissionRequest request) {
    ImportJobConfiguration jobConfiguration = (ImportJobConfiguration) request.getConfigFrameworkJob();

    // Initialize the map-reduce part (all sort of required classes, ...)
    request.setOutputDirectory(jobConfiguration.output.outputDirectory);

    // Delegate rest of the job to execution engine
    executionEngine.prepareImportSubmission(request);
  }

  /**
   * Callback that will be called only if we failed to submit the job to the
   * remote cluster.
   */
  private static void destroySubmission(SubmissionRequest request) {
    CallbackBase baseCallbacks = request.getConnectorCallbacks();

    Class<? extends Destroyer> destroyerClass = baseCallbacks.getDestroyer();
    Destroyer destroyer = (Destroyer) ClassUtils.instantiate(destroyerClass);

    if(destroyer == null) {
      throw  new SqoopException(FrameworkError.FRAMEWORK_0006,
        "Can't create destroyer instance: " + destroyerClass.getName());
    }

    // Initialize submission from connector perspective
    destroyer.run(request.getConnectorContext());
  }

  public static MSubmission stop(long jobId) {
    Repository repository = RepositoryManager.getRepository();
    MSubmission submission = repository.findSubmissionLastForJob(jobId);

    if(!submission.getStatus().isRunning()) {
      throw new SqoopException(FrameworkError.FRAMEWORK_0003,
        "Job with id " + jobId + " is not running");
    }

    String externalId = submission.getExternalId();
    submissionEngine.stop(externalId);

    // Fetch new information to verify that the stop command has actually worked
    update(submission);

    // Return updated structure
    return submission;
  }

  public static MSubmission status(long jobId) {
    Repository repository = RepositoryManager.getRepository();
    MSubmission submission = repository.findSubmissionLastForJob(jobId);

    if(submission == null) {
      return new MSubmission(jobId, new Date(), SubmissionStatus.NEVER_EXECUTED);
    }

    update(submission);

    return submission;
  }

  private static void update(MSubmission submission) {
    double progress  = -1;
    Counters counters = null;
    String externalId = submission.getExternalId();
    SubmissionStatus newStatus = submissionEngine.status(externalId);
    String externalLink = submissionEngine.externalLink(externalId);

    if(newStatus.isRunning()) {
      progress = submissionEngine.progress(externalId);
    } else {
      counters = submissionEngine.stats(externalId);
    }

    submission.setStatus(newStatus);
    submission.setProgress(progress);
    submission.setCounters(counters);
    submission.setExternalLink(externalLink);
    submission.setLastUpdateDate(new Date());

    RepositoryManager.getRepository().updateSubmission(submission);
  }

  private static class PurgeThread extends Thread {
    public PurgeThread() {
      super("PurgeThread");
    }

    public void run() {
      LOG.info("Starting submission manager purge thread");

      while(running) {
        try {
          LOG.info("Purging old submissions");
          Date threshold = new Date((new Date()).getTime() - purgeThreshold);
          RepositoryManager.getRepository().purgeSubmissions(threshold);
          Thread.sleep(purgeSleep);
        } catch (InterruptedException e) {
          LOG.debug("Purge thread interrupted", e);
        }
      }

      LOG.info("Ending submission manager purge thread");
    }
  }

  private static class UpdateThread extends Thread {
     public UpdateThread() {
      super("UpdateThread");
    }

    public void run() {
      LOG.info("Starting submission manager update thread");

      while(running) {
        try {
          LOG.debug("Updating running submissions");

          // Let's get all running submissions from repository to check them out
          List<MSubmission> unfinishedSubmissions =
            RepositoryManager.getRepository().findSubmissionsUnfinished();

          for(MSubmission submission : unfinishedSubmissions) {
            update(submission);
          }

          Thread.sleep(updateSleep);
        } catch (InterruptedException e) {
          LOG.debug("Purge thread interrupted", e);
        }
      }

      LOG.info("Ending submission manager update thread");
    }
  }

  private FrameworkManager() {
    // Instantiation of this class is prohibited
  }
}
