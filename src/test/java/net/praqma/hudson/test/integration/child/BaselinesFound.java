package net.praqma.hudson.test.integration.child;

import java.io.File;

import hudson.model.AbstractBuild;
import hudson.model.Result;
import net.praqma.clearcase.exceptions.ClearCaseException;
import net.praqma.clearcase.test.junit.CoolTestCase;
import net.praqma.clearcase.ucm.entities.Activity;
import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.entities.Baseline.LabelBehaviour;
import net.praqma.clearcase.ucm.entities.Project.PromotionLevel;
import net.praqma.clearcase.ucm.view.UCMView;
import net.praqma.clearcase.util.ExceptionUtils;
import net.praqma.hudson.test.CCUCMTestCase;
import net.praqma.util.debug.Logger;

public class BaselinesFound extends CCUCMTestCase {

	private static Logger logger = Logger.getLogger();
	
	public AbstractBuild<?, ?> initiateBuild( String projectName, String uniqueTestVobName, boolean recommend, boolean tag, boolean description, boolean fail ) throws Exception {
		return initiateBuild( projectName, uniqueTestVobName, "child", recommend, tag, description, fail );
	}

	public void testNoOptions() throws Exception {
		String un = setupCC( false );
		
		/**/
		String viewtag = un + "_one_dev";
		System.out.println( "VIEW: " + CoolTestCase.context.views.get( viewtag ) );
		File path = new File( CoolTestCase.context.mvfs + "/" + un + "_one_dev/" + un );
				
		System.out.println( "PATH: " + path );
		
		Stream stream = Stream.get( un + "_one_dev", coolTest.getPVob() );
		Activity activity = Activity.create( "ccucm-activity", stream, coolTest.getPVob(), true, "ccucm activity", null, path );
		UCMView.setActivity( activity, path, null, null );
		
		try {
			coolTest.addNewContent( CoolTestCase.context.components.get( "Model" ), path, "test2.txt" );
		} catch( ClearCaseException e ) {
			ExceptionUtils.print( e, System.out, true );
		}
		Baseline.create( "baseline-for-test", CoolTestCase.context.components.get( "_System" ), path, LabelBehaviour.FULL, false );
		
		AbstractBuild<?, ?> build = initiateBuild( "no-options-" + un, un, false, false, false, false );
		
		/* Build validation */
		assertTrue( build.getResult().isBetterOrEqualTo( Result.SUCCESS ) );
		
		/* Expected build baseline */
		logger.info( "Build baseline: " + getBuildBaseline( build ) );
		
		Baseline baseline = CoolTestCase.context.baselines.get( "model-1" );
		
		assertBuildBaseline( baseline, build );
		assertFalse( isRecommended( baseline, build ) );
		assertNull( getTag( baseline, build ) );
		samePromotionLevel( baseline, PromotionLevel.BUILT );
		
		testCreatedBaseline( build );
	}
	
}
