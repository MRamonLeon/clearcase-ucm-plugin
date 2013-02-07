package net.praqma.jenkins.clearcaseucm.strategies.changelog;

import hudson.FilePath;
import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.jenkins.clearcaseucm.model.ChangeLogProducer;
import net.praqma.jenkins.clearcaseucm.remote.PreviousBaselineDiff;

import java.io.IOException;

/**
 * @author cwolfgang
 *         Date: 07-02-13
 *         Time: 14:42
 */
public class PreviousBaseline extends ChangeLogProducer {

    public PreviousBaseline( FilePath workspace ) {
        super( workspace );
    }

    @Override
    public String produce( Baseline baseline ) throws IOException, InterruptedException {
        return workspace.act( new PreviousBaselineDiff( baseline ) );
    }
}
