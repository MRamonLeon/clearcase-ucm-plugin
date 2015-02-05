package net.praqma.hudson.test.integration.sibling;


import net.praqma.hudson.test.BaseTestClass;
import org.junit.Rule;
import org.junit.Test;

import hudson.model.AbstractBuild;
import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.entities.Project.PromotionLevel;
import net.praqma.hudson.test.SystemValidator;

import net.praqma.clearcase.test.junit.ClearCaseRule;
import net.praqma.hudson.scm.pollingmode.PollSiblingMode;


public class BaselinesFound extends BaseTestClass {
	
	@Rule
	public ClearCaseRule ccenv = new ClearCaseRule( "ccucm", "setup-interproject.xml" );
		
	public AbstractBuild<?, ?> initiateBuild( String projectName, boolean recommend, boolean tag, boolean description, boolean fail ) throws Exception {
        PollSiblingMode mode = new PollSiblingMode("INTIAL");
        mode.setCreateBaseline(true);
        mode.setUseHyperLinkForPolling(false);
		return jenkins.initiateBuild( projectName, mode, "_System@" + ccenv.getPVob(), "two_int@" + ccenv.getPVob(), recommend, tag, description, fail);
	}

	@Test
	public void basicSibling() throws Exception {
		
		Stream one = ccenv.context.streams.get( "one_int" );
		Stream two = ccenv.context.streams.get( "two_int" );
		one.setDefaultTarget( two );
		
		/* The baseline that should be built */
		Baseline baseline = ccenv.context.baselines.get( "model-1" );
		
		AbstractBuild<?, ?> build = initiateBuild( "no-options-" + ccenv.getUniqueName(), false, false, false, false );

		/* Validate */
		SystemValidator validator = new SystemValidator( build ).
                validateBuild( build.getResult() ).
                validateBuiltBaseline( PromotionLevel.BUILT, baseline, false ).
                validateCreatedBaseline( true );
		validator.validate();
	}
    
    @Test
	public void basicSiblingUsingHlink() throws Exception {
		
		Stream one = ccenv.context.streams.get( "one_int" );
		Stream two = ccenv.context.streams.get( "two_int" );
		
		/* The baseline that should be built */
		Baseline baseline = ccenv.context.baselines.get( "model-1" );
		
		AbstractBuild<?, ?> build = initiateBuild( "no-options-hlink" + ccenv.getUniqueName(), false, false, false, false );

		/* Validate */
		SystemValidator validator = new SystemValidator( build ).
                validateBuild( build.getResult() ).
                validateBuiltBaseline( PromotionLevel.BUILT, baseline, false ).
                validateCreatedBaseline( true );
		validator.validate();
	}

}
