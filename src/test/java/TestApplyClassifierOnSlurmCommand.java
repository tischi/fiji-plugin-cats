import de.embl.cba.cats.ui.ApplyClassifierOnSlurmCommand;
import de.embl.cba.cats.ui.ApplyClassifierAdvancedCommand;
import de.embl.cba.cats.utils.IOUtils;
import de.embl.cba.cats.utils.IntervalUtils;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImageJ;
import net.imglib2.FinalInterval;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static de.embl.cba.cats.utils.IntervalUtils.Z;


public class TestApplyClassifierOnSlurmCommand
{

    public static void main ( String... args )
    {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        ImagePlus imp = IJ.openImage( TestApplyClassifierOnSlurmCommand.class.getResource( "fib-sem--cell--8x8x8nm.zip" ).getFile() );

        FinalInterval fullImageInterval = IntervalUtils.getInterval( imp );
        long[] min = new long[ 5 ];
        long[] max = new long[ 5 ];
        fullImageInterval.min( min );
        fullImageInterval.max( max );
        min[ Z ] = 0; max[ Z ] = 5;
        FinalInterval interval = new FinalInterval( min, max );

        String inputImagePath = "/Volumes/cba/tischer/projects/em-automated-segmentation--data/fib-sem--cell--8x8x8nm.zip";
        String outputDirectory = "/Volumes/cba/tischer/projects/em-automated-segmentation--data/fib-sem--cell--classification/";
        File classifierFile = new File( "/Volumes/cba/tischer/projects/em-automated-segmentation--data/fib-sem--cell.classifier" );

        Map< String, Object > parameters = new HashMap<>(  );

        parameters.put( ApplyClassifierOnSlurmCommand.USER_NAME, "tischer" );

        parameters.put( ApplyClassifierOnSlurmCommand.PASSWORD, "OlexOlex" );

        parameters.put( ApplyClassifierOnSlurmCommand.INTERVAL, interval );

        parameters.put( ApplyClassifierAdvancedCommand.CLASSIFIER_FILE, classifierFile );

        parameters.put( IOUtils.INPUT_IMAGE_FILE, inputImagePath );

        parameters.put( IOUtils.INPUT_MODALITY, IOUtils.OPEN_USING_IMAGEJ1 );

        parameters.put( IOUtils.OUTPUT_DIRECTORY, outputDirectory );

        parameters.put( ApplyClassifierOnSlurmCommand.NUM_WORKERS, 16 );

        parameters.put( ApplyClassifierOnSlurmCommand.JOB_STATUS_MONITORING_INTERVAL, 15 );

        ij.command().run( ApplyClassifierOnSlurmCommand.class, false, parameters );

    }
}
