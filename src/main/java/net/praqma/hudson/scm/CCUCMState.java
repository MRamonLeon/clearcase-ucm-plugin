package net.praqma.hudson.scm;

import hudson.FilePath;
import hudson.model.Build;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.util.CopyOnWriteList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.ucm.entities.Project;
import java.util.ConcurrentModificationException;

import net.praqma.clearcase.ucm.entities.Component;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.view.SnapshotView;
import net.praqma.util.debug.Logger;
import net.praqma.util.debug.LoggerSetting;
import net.praqma.util.debug.Logger.LogLevel;
import net.praqma.util.debug.appenders.FileAppender;

/**
 * This is the state object for the CCUCM jobs
 * 
 * @author wolfgang
 * @deprecated
 */
public class CCUCMState {

	private CopyOnWriteList<State> states = new CopyOnWriteList<State>();
	private static final String linesep = System.getProperty( "line.separator" );
	private Logger logger = Logger.getLogger();
	private static final String tag = "ccucmstate";
	
	public CCUCMState() {
		try {
			FileAppender fa = new FileAppender( new File( System.getProperty( "user.home" ) + "/ccucmstate.log" ) );
			fa.setTag( tag );
			fa.setTemplate( "%datetime [%caller] %message%newline" );
			fa.setMinimumLevel( LogLevel.WARNING );
			//fa.setMinimumLevel( LogLevel.DEBUG );
			Logger.addAppender( fa );
		} catch( IOException e ) {
			
		}
	}

	/**
	 * Get a state given job name and job number
	 * 
	 * @param jobName
	 *            the hudson job name
	 * @param jobNumber
	 *            the hudson job number
	 * @return
	 */
	public State getState( String jobName, Integer jobNumber ) throws IllegalStateException {
		for( State s : states ) {
			try {
				if( s.getJobName().equals( jobName ) && s.getJobNumber().equals( jobNumber ) ) {
					return s;
				}
			} catch( NullPointerException e ) {
				/* It's ok, let's move on */
			}
		}
		
		logger.info( "missing " + jobName + " " + jobNumber, tag );
		throw new IllegalStateException( jobName + " " + jobNumber + " does not exist" );
	}
	
	public List<State> getStates( String jobName ) {
		List<State> tmpstates = new ArrayList<State>();
		
		for( State s : states ) {
			try {
				if( s.getJobName().equals( jobName ) ) {
					tmpstates.add( s );
				}
			} catch( NullPointerException e ) {
				/* It's ok, let's move on */
			}
		}
		
		return tmpstates;
	}
	
	public State create( String jobName, Integer jobNumber) {
		State s = new State( jobName, jobNumber );
		logger.info( "creating " + jobName + " " + jobNumber, tag );
		addState( s );
		return s;		
	}

	/**
	 * 
	 * @param jobName
	 * @param jobNumber
	 * @return
	 * @deprecated
	 */
	public boolean removeState( String jobName, Integer jobNumber ) {
		synchronized( states ) {
			for( State s : states ) {
				if( s.getJobName().equals( jobName ) && s.getJobNumber().equals( jobNumber ) ) {
					states.remove( s );
					logger.info( "removing " + jobName + " " + jobNumber, tag );
					return true;
				}
			}
		}

		return false;
	}

	public synchronized boolean removeState( State state ) {
		logger.info( "removing " + state.getJobName() + " " + state.getJobNumber(), tag );
		return states.remove( state );
	}
	
	public synchronized void signalFault( String jobName, Integer jobNumber ) {
		logger.fatal( "FAULT: " + jobName + " " + jobNumber, tag );
	}

	public synchronized void addState( State state ) {
		logger.info( "adding " + state.getJobName() + " " + state.getJobNumber(), tag );
		this.states.add( state );
	}

	public State getStateByBaseline( String jobName, String baseline ) {
		for( State s : states ) {
			if( s.getJobName().equals( jobName ) && s.getBaseline() != null && s.getBaseline().getFullyQualifiedName().equals( baseline ) ) {
				return s;
			}
		}

		return null;
	}

	public boolean stateExists( State state ) {
		return stateExists( state.jobName, state.jobNumber );
	}

	public boolean stateExists( String jobName, Integer jobNumber ) {
		for( State s : states ) {
			if( s.getJobName().equals( jobName ) && s.getJobNumber() == jobNumber ) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 
	 * @param project
	 * @return
	 * @deprecated
	 */
	public int recalculate( AbstractProject<?, ?> project ) {
		int count = 0;

		try {
			State s = null;
			Iterator<State> it = states.iterator();

			while( it.hasNext() ) {
				s = it.next();
	
				Integer bnum = s.getJobNumber();
				Object o = project.getBuildByNumber( bnum );
				
				/* If o is null it is probably because it's still in the polling phase */
				if( o != null ) {
					Build bld = (Build) o;
					/* The job is not running */
					if( !bld.isLogUpdated() ) {
					//if( bld.getNextBuild() != null && !bld.getNextBuild().hasntStartedYet() ) {
						it.remove();
						logger.info( "removing " + s.getJobName() + " " + s.getJobNumber(), tag );
						count++;
					}
				}
			}
		} catch( ConcurrentModificationException e ) {
			logger.warning( "Concurrency warning in CCUCMState" );
		} catch( NullPointerException e ) {
			logger.warning( "This should not happen" );
		}

		return count;
	}

	public int size() {
		return states.size();
	}

	public String stringify() {
		return states.getView().toString();
	}

	@Deprecated
	public class State {

		private Baseline baseline;
		private Stream stream;
		private Component component;
		private boolean doPostBuild = true;
		private Project.PromotionLevel plevel;
		private String loadModule;
		private String jobName;
		private Integer jobNumber;
		private boolean addedByPoller = false;
		private long multiSiteFrequency = 0;
		private List<Baseline> baselines = null;
		private Polling polling;
		private Unstable unstable;
		private boolean needsToBeCompleted = true;
		private SnapshotView snapView;
		private SnapshotView deliverView;
		private boolean createBaseline = true;
		private String nameTemplate;

		private AbstractBuild<?, ?> build;
		private TaskListener listener;

		private ClearCaseChangeset changeset;

		private boolean setDescription = false;
		private boolean makeTag = false;
		private boolean recommend = false;
		private boolean forceDeliver = false;

		private LoggerSetting loggerSetting;

		private FilePath workspace;

		private String error;

		public State() {
		}

		public State( String jobName, Integer jobNumber ) {
			this.jobName = jobName;
			this.jobNumber = jobNumber;
		}

		public State( String jobName, Integer jobNumber, Baseline baseline, Stream stream, Component component, boolean doPostBuild ) {
			this.jobName = jobName;
			this.jobNumber = jobNumber;
			this.baseline = baseline;
			this.stream = stream;
			this.component = component;
			this.doPostBuild = doPostBuild;
		}

		@Deprecated
		public void save() {
			CCUCMState.this.addState( this );
		}

		public boolean remove() {
			logger.info( "removing " + getJobName() + " " + getJobNumber(), tag );
			return CCUCMState.this.removeState( this );
		}

		public Baseline getBaseline() {
			return baseline;
		}

		public void setBaseline( Baseline baseline ) {
			this.baseline = baseline;
		}

		public Stream getStream() {
			return stream;
		}

		public Polling getPolling() {
			return polling;
		}

		public void setPolling( Polling polling ) {
			this.polling = polling;
		}

		public Unstable getUnstable() {
			return unstable;
		}

		public void setUnstable( Unstable unstable ) {
			this.unstable = unstable;
		}

		public void setStream( Stream stream ) {
			this.stream = stream;
		}

		public Component getComponent() {
			return component;
		}

		public void setComponent( Component component ) {
			this.component = component;
		}

		public boolean doPostBuild() {
			return doPostBuild;
		}

		public void setPostBuild( boolean doPostBuild ) {
			this.doPostBuild = doPostBuild;
		}

		public String getJobName() {
			return jobName;
		}

		public void setJobName( String jobName ) {
			this.jobName = jobName;
		}

		public Integer getJobNumber() {
			return jobNumber;
		}

		public void setJobNumber( Integer jobNumber ) {
			this.jobNumber = jobNumber;
		}

		public void setPlevel( Project.PromotionLevel plevel ) {
			this.plevel = plevel;
		}

		public Project.PromotionLevel getPlevel() {
			return plevel;
		}

		public String stringify() {
			StringBuffer sb = new StringBuffer();

			sb.append( "Job name      : " + this.jobName + linesep );
			sb.append( "Job number    : " + this.jobNumber + linesep );
			sb.append( "Component     : " + this.component + linesep );
			sb.append( "Stream        : " + this.stream + linesep );
			sb.append( "Baseline      : " + this.baseline + linesep );
			sb.append( "Poll level    : " + ( this.plevel != null ? this.plevel.toString() : "Missing" ) + linesep );
			sb.append( "Load Module   : " + this.loadModule + linesep );
			sb.append( "Baseline list : " + ( this.baselines != null ? this.baselines.size() : "0" ) + linesep );
			sb.append( "Added by poll : " + ( this.addedByPoller ? "Yes" : "No" ) + linesep );
			sb.append( "Multi site    : " + ( this.multiSiteFrequency > 0 ? StoredBaselines.milliToMinute( this.multiSiteFrequency ) : "N/A" ) + linesep );
			sb.append( "postBuild     : " + this.doPostBuild + linesep );
			sb.append( "needsToBeComp : " + this.needsToBeCompleted + linesep );

			return sb.toString();
		}

		public String toString() {
			return "(" + jobName + ", " + jobNumber + ")";
		}

		public boolean equals( Object other ) {
			if( other instanceof State ) {
				if( this.getJobName().equals( ( (State) other ).getJobName() ) && this.getJobNumber().equals( ( (State) other ).getJobNumber() ) ) {
					return true;
				}

			}

			return false;
		}

		public void setLoadModule( String loadModule ) {
			this.loadModule = loadModule;
		}

		public String getLoadModule() {
			return loadModule;
		}

		public void setAddedByPoller( boolean addedByPoller ) {
			this.addedByPoller = addedByPoller;
		}

		public boolean isAddedByPoller() {
			return addedByPoller;
		}

		public void setMultiSiteFrequency( long multiSiteFrquency ) {
			this.multiSiteFrequency = multiSiteFrquency;
		}

		public long getMultiSiteFrquency() {
			return multiSiteFrequency;
		}

		public boolean isMultiSite() {
			return this.multiSiteFrequency > 0;
		}

		public void setBaselines( List<Baseline> baselines ) {
			this.baselines = baselines;
		}

		public List<Baseline> getBaselines() {
			return baselines;
		}

		public void setNeedsToBeCompleted( boolean s ) {
			this.needsToBeCompleted = s;
		}

		public boolean needsToBeCompleted() {
			return this.needsToBeCompleted;
		}

		public void setCreatebaseline( boolean tf ) {
			this.createBaseline = tf;
		}

		public boolean createBaseline() {
			return this.createBaseline;
		}

		public void setNameTemplate( String template ) {
			this.nameTemplate = template;
		}

		public String getNameTemplate() {
			return this.nameTemplate;
		}

		public String getError() {
			return error;
		}

		public void setError( String error ) {
			this.error = error;
		}

		public boolean isSetDescription() {
			return setDescription;
		}

		public void setSetDescription( boolean setDescription ) {
			this.setDescription = setDescription;
		}

		public boolean isMakeTag() {
			return makeTag;
		}

		public void setMakeTag( boolean makeTag ) {
			this.makeTag = makeTag;
		}

		public boolean doRecommend() {
			return recommend;
		}

		public void setRecommend( boolean recommend ) {
			this.recommend = recommend;
		}

		public ClearCaseChangeset getChangeset() {
			return changeset;
		}

		public void setChangeset( ClearCaseChangeset changeset ) {
			this.changeset = changeset;
		}

		public FilePath getWorkspace() {
			return workspace;
		}

		public void setWorkspace( FilePath workspace ) {
			this.workspace = workspace;
		}

		public LoggerSetting getLoggerSetting() {
			return loggerSetting;
		}

		public void setLoggerSetting( LoggerSetting loggerSetting ) {
			this.loggerSetting = loggerSetting;
		}

		public AbstractBuild<?, ?> getBuild() {
			return build;
		}

		public void setBuild( AbstractBuild<?, ?> build ) {
			this.build = build;
		}

		public TaskListener getListener() {
			return listener;
		}

		public void setListener( TaskListener listener ) {
			this.listener = listener;
		}

		public boolean getForceDeliver() {
			return forceDeliver;
		}

		public void setForceDeliver( boolean forceDeliver ) {
			this.forceDeliver = forceDeliver;
		}

	}
}
