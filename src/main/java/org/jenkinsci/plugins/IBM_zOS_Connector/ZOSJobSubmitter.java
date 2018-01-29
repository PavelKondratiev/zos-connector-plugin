package org.jenkinsci.plugins.IBM_zOS_Connector;

import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.logging.Logger;

/**
 * <h1>ZOSJobSubmitter</h1>
 * Build step action for submitting JCL job.
 *
 * @author <a href="mailto:candiduslynx@gmail.com">Alexander Shcherbakov</a>
 * @version 1.0
 */
public class ZOSJobSubmitter extends Builder implements SimpleBuildStep {
    /**
     * Simple logger.
     */
    private static final Logger logger = Logger.getLogger(ZOSJobSubmitter.class.getName());
    /**
     * LPAR name or IP address.
     */
    private String server;
    /**
     * FTP port for connection
     */
    private int port;
    /**
     * UserID.
     */
    private String userID;
    /**
     * User password.
     */
    private String password;
    /**
     * Whether need to wait for the job completion.
     */
    private boolean wait;
    /**
     * Whether FTP server is in JESINTERFACELEVEL=1.
     */
    private boolean JESINTERFACELEVEL1;
    /**
     * Whether the job log is to be deleted upon job end.
     */
    private boolean deleteJobFromSpool;
    /**
     * Whether the job log is to be printed to Console.
     */
    private boolean jobLogToConsole;
    /**
     * Time to wait for the job to end. If set to <code>0</code> the buil will wait forever.
     */
    private int waitTime;
    /**
     * JCL of the job to be submitted.
     */
    private String job;
    /**
     * MaxCC to decide that job ended OK.
     */
    private String MaxCC;

    /**
     * Constructor. Invoked when 'Apply' or 'Save' button is pressed on the project configuration page.
     *
     * @param server             LPAR name or IP address.
     * @param port               FTP port to connect to.
     * @param userID             UserID.
     * @param password           User password.
     * @param wait               Whether we need to wait for the job completion.
     * @param waitTime           Maximum wait time. If set to <code>0</code> will wait forever.
     * @param deleteJobFromSpool Whether the job log will be deleted from the spool after end.
     * @param jobLogToConsole    Whether the job log will be printed to console.
     * @param job                JCL of the job to be submitted.
     * @param JESINTERFACELEVEL1 Is FTP server configured for JESINTERFACELEVEL=1?
     */
    @DataBoundConstructor
    public ZOSJobSubmitter(
            String server,
            int port,
            String userID,
            String password,
            boolean wait,
            int waitTime,
            boolean deleteJobFromSpool,
            boolean jobLogToConsole,
            String job,
            String MaxCC,
            boolean JESINTERFACELEVEL1) {
        // Copy values
        this.server = server.replaceAll("\\s", "");
        this.port = port;
        this.userID = userID.replaceAll("\\s", "");
        this.password = password.replaceAll("\\s", "");
        this.wait = wait;
        this.waitTime = waitTime;
        this.JESINTERFACELEVEL1 = JESINTERFACELEVEL1;
        this.deleteJobFromSpool = deleteJobFromSpool;
        this.jobLogToConsole = jobLogToConsole;
        this.job = job;
        if (MaxCC == null || MaxCC.isEmpty()) {
            this.MaxCC = "0000";
        } else {
            this.MaxCC = MaxCC;
            if (this.MaxCC.length() < 4) {
                this.MaxCC = "000".substring(0, 4 - this.MaxCC.length()) + this.MaxCC;
            }
        }
    }

    /**
     * Submit the job for execution.
     *
     * @param run       Current run
     * @param workspace Current workspace
     * @param launcher  Current launcher
     * @param listener  Current listener
     *                  <p>
     *                  <br> Always <code>true</code> if <b><code>wait</code></b> is <code>false</code>.
     * @see ZFTPConnector
     */
    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        // variables to be expanded
        String _server = this.server;
        String _userID = this.userID;
        String _password = this.password;
        String _job = this.job;
        String _MaxCC = this.MaxCC;

        String logPrefix = run.getParent().getDisplayName() + " " + run.getId() + ": ";
        try {
            logger.info(logPrefix + "will expand variables");
            EnvVars environment = run.getEnvironment(listener);
            _server = environment.expand(_server);
            _userID = environment.expand(_userID);
            _password = environment.expand(_password);
            _job = environment.expand(_job);
            _MaxCC = environment.expand(_MaxCC);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            throw new AbortException(e.getMessage());
        }

        // Get connector.
        ZFTPConnector zFTPConnector = new ZFTPConnector(_server,
                this.port,
                _userID,
                _password,
                this.JESINTERFACELEVEL1,
                logPrefix);
        // Read the JCL.
        InputStream inputStream = new ByteArrayInputStream(_job.getBytes(Charset.defaultCharset()));
        // Prepare the output stream.
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Submit the job.
        boolean result = zFTPConnector.submit(inputStream, this.wait, this.waitTime, outputStream, this.deleteJobFromSpool, listener);

        // Get CC.
        String printableCC = zFTPConnector.getJobCC();
        if (printableCC != null)
            printableCC = printableCC.replaceAll("\\s+", "");
        else
            printableCC = "";

        // Print the info about the job
        logger.info("Job [" + zFTPConnector.getJobID() + "] processing finished.");
        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append("Job [");
        reportBuilder.append(zFTPConnector.getJobID());
        reportBuilder.append("] processing ");
        if (!printableCC.matches("\\d+")) {
            if (printableCC.startsWith("ABEND")) {
                reportBuilder.append("ABnormally ENDed. ABEND code = [");
            } else {
                reportBuilder.append("failed. Reason: [");
            }
        } else {
            reportBuilder.append("finished. Captured RC = [");
        }
        reportBuilder.append(printableCC);
        reportBuilder.append("]");
        listener.getLogger().println(reportBuilder.toString());

        // If wait was requested try to save the job log.
        if (this.wait) {
            if (this.jobLogToConsole){
                listener.getLogger().println(outputStream.toString("US-ASCII"));
            }
            // Save the log.
            try {
                FilePath savedOutput = new FilePath(workspace,
                        String.format("%s [%s] (%s - %s) %s - %s.log",
                                zFTPConnector.getJobName(),
                                printableCC,
                                _server,
                                zFTPConnector.getJobID(),
                                run.getParent().getDisplayName(),
                                run.getId()
                        ));
                outputStream.writeTo(savedOutput.write());
                outputStream.close();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                throw new AbortException(e.getMessage());
            }
        } else {
            printableCC = "0000"; //set RC = 0
        }

        if (!(result && (this.JESINTERFACELEVEL1 || (_MaxCC.compareTo(printableCC) >= 0)))) {
            throw new AbortException("z/OS job failed with CC " + printableCC);
        }
    }

    /**
     * Get LPAR name of IP address.
     *
     * @return <b><code>server</code></b>
     */
    public String getServer() {
        return this.server;
    }

    /**
     * Get FTP port to connect to.
     *
     * @return <b><code>port</code></b>
     */
    public int getPort() {
        return this.port;
    }

    /**
     * Get UserID.
     *
     * @return <b><code>userID</code></b>
     */
    public String getUserID() {
        return this.userID;
    }

    /**
     * Get User Password.
     *
     * @return <b><code>password</code></b>
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * Get wait.
     *
     * @return <b><code>wait</code></b>
     */
    public boolean getWait() {
        return this.wait;
    }

    /**
     * Get JESINTERFACELEVEL1.
     *
     * @return <b><code>JESINTERFACELEVEL1</code></b>
     */
    public boolean getJESINTERFACELEVEL1() {
        return this.JESINTERFACELEVEL1;
    }

    /**
     * Get deleteJobFromSpool.
     *
     * @return <b><code>deleteJobFromSpool</code></b>
     */
    public boolean getDeleteJobFromSpool() {
        return this.deleteJobFromSpool;
    }

    /**
     * Get jobLogToConsole.
     *
     * @return <b><code>jobLogToConsole</code></b>
     */
    public boolean getJobLogToConsole() {
        return this.jobLogToConsole;
    }

    /**
     * Get wait time.
     *
     * @return <b><code>waitTime</code></b>
     */
    public int getWaitTime() {
        return this.waitTime;
    }

    /**
     * Get Job.
     *
     * @return <b><code>Job</code></b>
     */
    public String getJob() {
        return this.job;
    }

    /**
     * @return <b><code>MaxCC of the job to be considered OK</code></b>
     */
    public String getMaxCC() {
        return this.MaxCC;
    }

    /**
     * Get descriptor for this class.
     *
     * @return descriptor for this class.
     */
    @Override
    public ZOSJobSubmitterDescriptor getDescriptor() {
        return (ZOSJobSubmitterDescriptor) super.getDescriptor();
    }

    /**
     * <h1>zOSJobSubmitterDescriptor</h1>
     * Descriptor for ZOSJobSubmitter.
     *
     * @author Alexander Shchrbakov (candiduslynx@gmail.com)
     * @version 1.0
     */
    @Extension
    public static final class ZOSJobSubmitterDescriptor extends BuildStepDescriptor<Builder> {
        /**
         * Primitive constructor.
         */
        public ZOSJobSubmitterDescriptor() {
            load();
        }

        /**
         * Function for validation of 'Server' field on project configuration page
         *
         * @param value Current server.
         * @return Whether server name looks OK.
         * @throws IOException
         * @throws ServletException
         */
        public FormValidation doCheckServer(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a server");
            return FormValidation.ok();
        }

        /**
         * Function for validation of 'User ID' field on project configuration page
         *
         * @param value Current userID.
         * @return Whether userID looks OK.
         * @throws IOException
         * @throws ServletException
         */
        public FormValidation doCheckUsername(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a username");
            return FormValidation.ok();
        }

        /**
         * Function for validation of 'Password' field on project configuration page
         *
         * @param value Current password.
         * @return Whether password looks OK.
         * @throws IOException
         * @throws ServletException
         */
        public FormValidation doCheckPassword(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a password");
            return FormValidation.ok();
        }

        /**
         * Function for validation of 'Job' field on project configuration page
         *
         * @param value Current job.
         * @return Whether job looks OK.
         * @throws IOException
         * @throws ServletException
         */
        public FormValidation doCheckInput(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set an input");
            return FormValidation.ok();
        }

        /**
         * Function for validation of 'Wait Time' field on project configuration page
         *
         * @param value Current wait time.
         * @return Whether wait time looks OK.
         * @throws IOException
         * @throws ServletException
         */
        public FormValidation doCheckWaitTime(@QueryParameter String value)
                throws IOException, ServletException {
            if (!value.matches("\\d*"))
                return FormValidation.error("Value must be numeric");
            if (Integer.parseInt(value) < 0)
                return FormValidation.error("Value must not be negative");
            return FormValidation.ok();
        }

        public FormValidation doCheckMaxCC(@QueryParameter String value)
                throws IOException, ServletException {
            if (!value.matches("(\\d{1,4})|(\\s*)"))
                return FormValidation.error("Value must be 4 decimal digits or empty");
            return FormValidation.ok();

        }

        /**
         * If this build step can be used with the project.
         *
         * @param aClass Project description class.
         * @return Always <code>true</code>.
         */
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * Get printable name.
         *
         * @return Printable name for project configuration page.
         */
        public String getDisplayName() {
            return "Submit zOS Job";
        }
    }
}