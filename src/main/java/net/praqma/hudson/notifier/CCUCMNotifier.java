package net.praqma.hudson.notifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import jcifs.dcerpc.msrpc.netdfs;

import org.kohsuke.stapler.StaplerRequest;

import net.praqma.clearcase.ucm.UCMException;
import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.Cool;
import net.praqma.clearcase.ucm.entities.UCMEntity;
import net.praqma.hudson.Config;
import net.praqma.hudson.exception.NotifierException;
import net.praqma.hudson.remoting.RemoteDeliverComplete;
import net.praqma.hudson.remoting.Util;
import net.praqma.hudson.scm.CCUCMScm;
import net.praqma.hudson.scm.CCUCMState.State;
import net.praqma.util.debug.PraqmaLogger;
import net.praqma.util.debug.PraqmaLogger.Logger;
import net.sf.json.JSONObject;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.remoting.Future;
import hudson.remoting.Pipe;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

/**
 * CCUCMNotifier perfoms the user-chosen CCUCM post-build actions
 *
 * @author Troels Selch
 * @author Margit Bennetzen
 *
 */
public class CCUCMNotifier extends Notifier {
    /* Old skool promotion */

    private boolean recommended;
    //private Baseline baseline;
    private PrintStream hudsonOut;
    private boolean makeTag;
    private boolean setDescription;
    private Status status;
    private String id = "";
    private Logger logger = null;
    private UCMDeliver ucmDeliverObj = null;
    private String jobName = "";
    private Integer jobNumber = 0;
    
    private SimpleDateFormat logformat  = new SimpleDateFormat( "yyyyMMdd-HHmmss" );

    /**
     * This constructor is used in the inner class <code>DescriptorImpl</code>.
     *
     * @param promote <ol start="0"><li>Baseline will not be promoted after the build</li>
     * <li>Baseline will be promoted after the build if stable</li>
     * <li>Baseline will be promoted after the build if unstable</li></ol>
     * @param recommended
     *            if <code>true</code>, the baseline will be marked
     *            'recommended' in ClearCase.
     * @param makeTag
     *            if <code>true</code>, CCUCM will set a Tag() on the baseline in
     *            ClearCase.
     * @param ucmDeliver The special deliver object, in which all the deliver parameters are encapsulated.

     */
    public CCUCMNotifier( boolean recommended, boolean makeTag, boolean setDescription, UCMDeliver ucmDeliver ) {
        this.recommended = recommended;
        this.makeTag = makeTag;
        this.setDescription = setDescription;

        /* Advanced */
        this.ucmDeliverObj = ucmDeliver;
    }

    /**
     * This indicates whether to let CCUCM run after(true) the job is done or before(false)
     */
    @Override
    public boolean needsToRunAfterFinalized() {
        return false;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        
        boolean result = true;
        hudsonOut = listener.getLogger();
        
        /* Preparing the logger */
        logger = PraqmaLogger.getLogger();
        logger.subscribeAll();
        File rdir = new File(logger.getPath());
        logger.setLocalLog(new File(rdir + System.getProperty("file.separator") + "log.log"));

        /* Prepare job variables */
        jobName = build.getParent().getDisplayName().replace(' ', '_');
        jobNumber = build.getNumber();

        /*
        logger.unsubscribeAll();
        if (build.getBuildVariables().get("include_classes") != null) {
            String[] is = build.getBuildVariables().get("include_classes").toString().split(",");
            for (String i : is) {
                logger.subscribe(i.trim());
            }
        }
        */

        Cool.setLogger(logger);

        status = new Status();

        this.id = "[" + jobName + "::" + jobNumber + "]";

        SCM scmTemp = null;
        /*TODO result is always true!!!!...
         * so we could move the if blok unless it should not always be true
         */
        if (result) {
            scmTemp = build.getProject().getScm();
            if (!(scmTemp instanceof CCUCMScm)) {
                listener.fatalError("[" + Config.nameShort + "] Not a CCUCM scm. This Post build action can only be used when polling from ClearCase with CCUCM plugin.");
                result = false;
            }
            scmTemp.toString();
        }

        State pstate = null;
        Baseline baseline = null;

        /* Only do this part if a valid CCUCMScm build */
        if (result) {
            /* Retrieve the CCUCM state */
            pstate = CCUCMScm.ccucm.getState(jobName, jobNumber);
            pstate.getLogger().debug("The valid state: " + pstate.stringify());

            /* Validate the state */
            if (pstate.doPostBuild() && pstate.getBaseline() != null) {
                logger.debug(id + "Post build");

                /* This shouldn't actually be necessary!?
                 * TODO Maybe the baseline should be re-Load()ed instead of creating a new object?  */
                String bl = pstate.getBaseline().getFullyQualifiedName();

                /* If no baselines found bl will be null and the post build section will not proceed */
                if (bl != null) {
                    try {
                        baseline = UCMEntity.getBaseline(bl);
                    } catch (UCMException e) {
                        logger.warning(id + "Could not initialize baseline.");
                        baseline = null;
                    }

                    if (baseline == null) {
                        /* If baseline is null, the user has already been notified in Console output from CCUCMScm.checkout() */
                        result = false;
                    }
                } else {
                    result = false;
                }

            } else {
                // Not performing any post build actions.
                result = false;
            }
        }

        /* There's a valid baseline, lets process it */
        if (result) {

            try {
                processBuild(build, launcher, listener, pstate);
                if (setDescription) {
                    build.setDescription(status.getBuildDescr());
                    hudsonOut.println("[" + Config.nameShort + "] Description set to - " + status.getBuildDescr());
                }

            } catch (NotifierException ne) {
                hudsonOut.println(ne.getMessage());
            } catch (IOException e) {
                hudsonOut.println("[" + Config.nameShort + "] Couldn't set build description.");
            }
        } else {
            String d = build.getDescription();
            if (d != null) {
                build.setDescription((d.length() > 0 ? d + "<br/>" : "") + "Nothing to do");
            }

            build.setResult(Result.NOT_BUILT);
        }

        /*
         * Removing baseline and job from collection, do this no matter what as
         * long as the SCM is CCUCM
         */
        if ((scmTemp instanceof CCUCMScm) && baseline != null) {
            boolean done2 = pstate.remove();
            logger.debug(id + "Removing job " + build.getNumber() + " from collection: " + done2);

            //logger.debug( "CCUCM FINAL=" + CCUCMScm.CCUCM.stringify() );

            if (pstate.isMultiSite()) {
                /* Trying to store baseline */
                //logger.debug( id + "Trying to store baseline" );
                if (!CCUCMScm.storedBaselines.addBaseline(pstate.getBaseline())) {
                    logger.warning(id + "Storing baseline failed.");
                }
            }
        }
        
        hudsonOut.println( "[" + Config.nameShort + "] Post build steps done" );

        return result;
    }

    /**
     * This is where all the meat is. When the baseline is validated, the actual post build steps are performed. <br>
     * First the baseline is delivered(if chosen), then tagged, promoted and recommended.
     * @param build The build object in which the post build action is selected
     * @param launcher The launcher of the build
     * @param listener The listener of the build
     * @param pstate The {@link CCUCMState} of the build.
     * @throws NotifierException
     */
    private void processBuild(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, State pstate) throws NotifierException {
        Result buildResult = build.getResult();

        VirtualChannel ch = launcher.getChannel();

        if (ch == null) {
            logger.debug("The channel was null");
        }

        FilePath workspace = build.getExecutor().getCurrentWorkspace();

        if (workspace == null) {
            logger.warning("Workspace is null");
            throw new NotifierException("Workspace is null");
        }

        hudsonOut.println("[" + Config.nameShort + "] Build result: " + buildResult);
        
        /* Initialize variables for post build steps */
        String sourcestream = "";
		try {
			sourcestream = pstate.getBaseline().getStream().getFullyQualifiedName();
		} catch (UCMException e2) {
			logger.warning( "Could not get name for source stream...." );
		}
        String targetstream = sourcestream;
        String sourcebaseline = pstate.getBaseline().getFullyQualifiedName();
        String targetbaseline = sourcebaseline;

        /* Finalize CCUCM, deliver + baseline 
         * Only do this for child and sibling polling */
        if( pstate.needsToBeCompleted() && pstate.getPolling().isPollingOther() ) {
            status.setBuildStatus(buildResult);
            
            /* Determine whether to complete or cancel */
            boolean complete = buildResult.isBetterThan( pstate.getUnstable().treatSuccessful() ? Result.FAILURE : Result.UNSTABLE );
            
            try {                
                hudsonOut.print("[" + Config.nameShort + "] " + ( complete ? "completing" : "cancelling" ) + " the deliver. ");
                Util.completeRemoteDeliver( workspace, listener, pstate, complete );
                hudsonOut.println("Success.");
                
                /* If deliver was completed, create the baseline */
                if( complete && pstate.getBaselineInformation().createBaseline ) {

                	try {
                        hudsonOut.print("[" + Config.nameShort + "] Creating baseline on Integration stream. ");
                        String name = pstate.getBaseline().getStream().getShortname() + "_" + logformat.format( new Date() );
                        if( pstate.getBaselineInformation().versionFrom == null || pstate.getBaselineInformation().versionFrom.equalsIgnoreCase( "default" ) ) {
                        	/* No op so far */
                        } else {
	                        String number = net.praqma.hudson.Util.CreateNumber(listener, build.getNumber(), pstate.getBaselineInformation().versionFrom, 
	                        													pstate.getBaselineInformation().buildnumberMajor, pstate.getBaselineInformation().buildnumberMinor, 
	                        													pstate.getBaselineInformation().buildnumberPatch, pstate.getBaselineInformation().buildnumberSequenceSelector, 
	                        													pstate.getStream().getDefaultTarget(), pstate.getComponent());
	                        name = pstate.getBaseline().getShortname() + "_" + number;
                        }
                        
                        targetbaseline = Util.createRemoteBaseline( workspace, listener, name, pstate.getBaseline().getComponent().getFullyQualifiedName(), pstate.getSnapView().GetViewRoot() );
                        
                        hudsonOut.println( targetbaseline );
                    } catch( Exception e ) {
                        hudsonOut.println(" Failed.");
                        logger.warning( "Failed to create baseline on stream" );
                        logger.warning( e );
                        /* We cannot recommend a baseline that is not created */
                        if( recommended ) {
                        	hudsonOut.println( "[" + Config.nameShort + "] Cannot recommend Baseline when not created" );
                        }
                        recommended = false;
                    }
                }
                
            } catch( Exception e ) {
                status.setBuildStatus(buildResult);
                status.setStable(false);
                hudsonOut.println("Failed.");
                logger.warning(e);
                
                /* We cannot recommend a baseline that is not created */
                if( recommended ) {
                	hudsonOut.println( "[" + Config.nameShort + "] Cannot recommend a baseline when deliver failed" );
                }
                recommended = false;
                
                /* If trying to complete and it failed, try to cancel it */
                if( complete ) {
                    try{
                        hudsonOut.print("[" + Config.nameShort + "] Trying to cancel the deliver. ");
                        Util.completeRemoteDeliver( workspace, listener, pstate, false );
                        hudsonOut.println("Success.");
                    } catch( Exception e1 ) {
                        hudsonOut.println(" Failed.");
                        logger.warning( "Failed to cancel deliver" );
                        logger.warning( e );
                    }
                } else {
                    logger.warning( "Failed to cancel deliver" );
                    logger.warning( e );
                }
            }
        } 
        
        
        if( pstate.getPolling().isPollingOther() ) {
        	targetstream = pstate.getStream().getFullyQualifiedName();
        }
    
        /* Remote post build step, common to all types */
        try {
            logger.debug(id + "Remote post build step");
            hudsonOut.println("[" + Config.nameShort + "] Performing common post build steps");

            final Pipe pipe = Pipe.createRemoteToLocal();

            Future<Status> f = null;
            
            String streamName = pstate.getPolling().isPollingOther() ? pstate.getBaseline().getStream().getFullyQualifiedName() : pstate.getStream().getFullyQualifiedName();
            f = workspace.actAsync(new RemotePostBuild(buildResult, status, listener, 
            		                                   makeTag, recommended, pstate.getUnstable(), 
            		                                   sourcebaseline, targetbaseline, sourcestream, targetstream, build.getParent().getDisplayName(), Integer.toString(build.getNumber()), logger/*, pout*/, pipe));

            status = f.get();

            logger.empty(status.getLog());
        } catch (Exception e) {
            status.setStable(false);
            logger.debug(id + "Something went wrong: " + e.getMessage());
            logger.warning(e);
            hudsonOut.println("[" + Config.nameShort + "] Error: Post build failed: " + e.getMessage());
        }

        /* If the promotion level of the baseline was changed on the remote */
        if (status.getPromotedLevel() != null) {
            pstate.getBaseline().setPromotionLevel(status.getPromotedLevel());
            logger.debug(id + "Baselines promotion level sat to " + status.getPromotedLevel().toString());
        }
        
        status.setBuildStatus(buildResult);
        
        if (!status.isStable()) {
            build.setResult(Result.UNSTABLE);
        }
    }

    public boolean isRecommended() {
        return recommended;
    }

    public boolean isMakeTag() {
        return makeTag;
    }

    public boolean isSetDescription() {
        return setDescription;
    }

    /* Advanced */
    public boolean isUcmDeliver() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.ucmDeliver;
        } else {
            return false;
        }
    }

    public String getAlternateTarget() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.alternateTarget;
        } else {
            return "";
        }
    }

    public String getBaselineName() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.baselineName;
        } else {
            return "";
        }
    }

    public String getVersionFrom() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.versionFrom;
        } else {
            return "";
        }
    }

    public String getBuildnumberSequenceSelector() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.buildnumberSequenceSelector;
        } else {
            return "";
        }
    }

    public String getbuildnumberMajor() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.buildnumberMajor;
        } else {
            return "";
        }
    }

    public String getbuildnumberMinor() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.buildnumberMinor;
        } else {
            return "";
        }
    }

    public String getbuildnumberPatch() {
        if (ucmDeliverObj != null) {
            return ucmDeliverObj.buildnumberPatch;
        } else {
            return "";
        }
    }

    /**
     * This class is used by Hudson to define the plugin.
     *
     * @author Troels Selch
     * @author Margit Bennetzen
     *
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(CCUCMNotifier.class);
            //logger.trace_function();
            load();
        }

        @Override
        public String getDisplayName() {
            //logger.trace_function();
            return "ClearCase UCM";
        }

        /**
         * Hudson uses this method to create a new instance of
         * <code>CCUCMNotifier</code>. The method gets information from Hudson
         * config page. This information is about the configuration, which
         * Hudson saves.
         */
        @Override
        public Notifier newInstance(StaplerRequest req, JSONObject formData) throws FormException {

            boolean recommended = req.getParameter("CCUCM.recommended") != null;
            boolean makeTag = req.getParameter("CCUCM.makeTag") != null;
            boolean setDescription = req.getParameter("CCUCM.setDescription") != null;

            boolean ucmDeliver = req.getParameter("CCUCM.ucmDeliver") != null;
            String alternateTarget = req.getParameter("CCUCM.alternateTarget");
            String baselineName = req.getParameter("CCUCM.baselineName");
            //      boolean apply4level = req.getParameter("CCUCM.apply4level") != null;
            String versionFrom = req.getParameter("CCUCM.versionFrom");
            String buildnumberSequenceSelector = req.getParameter("CCUCM.buildnumberSequenceSelector");

            String buildnumberMajor = req.getParameter("CCUCM.buildnumberMajor");
            String buildnumberMinor = req.getParameter("CCUCM.buildnumberMinor");
            String buildnumberPatch = req.getParameter("CCUCM.buildnumberPatch");

            UCMDeliver d = new UCMDeliver();

            d.ucmDeliver = ucmDeliver;
            d.alternateTarget = alternateTarget;
            d.baselineName = baselineName;
            //d.apply4level      = apply4level;
            d.versionFrom = versionFrom;
            d.buildnumberSequenceSelector = buildnumberSequenceSelector;
            d.buildnumberMajor = buildnumberMajor;
            d.buildnumberMinor = buildnumberMinor;
            d.buildnumberPatch = buildnumberPatch;

            save();

            return new CCUCMNotifier(recommended, makeTag, setDescription, d);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> arg0) {
            //logger.trace_function();
            return true;
        }
    }
}
