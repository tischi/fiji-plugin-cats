package de.embl.cba.trainableDeepSegmentation.commands;

/*
 * To the extent possible under law, the ImageJ developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */


import de.embl.cba.cluster.commands.Commands;
import de.embl.cba.trainableDeepSegmentation.utils.IOUtils;
import de.embl.cba.trainableDeepSegmentation.utils.StringUtils;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.*;
import net.imagej.ops.OpService;
import net.imglib2.FinalInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.thread.ThreadService;
import org.scijava.ui.UIService;
import de.embl.cba.trainableDeepSegmentation.*;
import de.embl.cba.trainableDeepSegmentation.results.Utils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static de.embl.cba.trainableDeepSegmentation.commands.ApplyClassifierCommand.PLUGIN_NAME;
import static de.embl.cba.trainableDeepSegmentation.utils.IntervalUtils.XYZT;

@Plugin(type = Command.class, menuPath = "Plugins>Segmentation>Development>"+PLUGIN_NAME )
public class ApplyClassifierCommand<T extends RealType<T>> implements Command
{
    public static final String PLUGIN_NAME = "Apply Classifier";

    @Parameter
    public UIService uiService;

    @Parameter
    public DatasetService datasetService;

    @Parameter
    public LogService logService;

    @Parameter
    public ThreadService threadService;

    @Parameter
    public OpService opService;

    @Parameter
    public StatusService statusService;

    @Parameter( label = "Input modality", choices = { IOUtils.OPEN_USING_IMAGE_J1, IOUtils.OPEN_USING_IMAGEJ1_IMAGE_SEQUENCE } , required = true )
    public String inputModality;

    @Parameter ( label = "Input image path" )
    public File inputImagePath;

    @Parameter (label = "Input image interval", required = false )
    public String inputImageInterval = WHOLE_IMAGE;
    public static final String INPUT_IMAGE_INTERVAL = "inputImageIntervalXYZT";
    public static final String WHOLE_IMAGE = "Whole image";

    @Parameter (label = "Classifier" )
    public File classifierFile;
    public static final String CLASSIFIER_FILE = "classifierFile";

    @Parameter( label = "Output modality", choices = { IOUtils.SHOW_AS_ONE_IMAGE, IOUtils.SAVE_AS_TIFF_STACKS, IOUtils.SAVE_AS_IMARIS } , required = true )
    public String outputModality;

    @Parameter( label = "Output folder", style = "directory" )
    public File outputDirectory;
    public static final String OUTPUT_DIRECTORY = "outputDirectory";

    @Parameter( label = "Quit ImageJ after running", required = false )
    public boolean quitAfterRun = false;
    public static final String QUIT_AFTER_RUN = "quitAfterRun";

    @Parameter( label = "Memory [MB]" )
    public long memory;
    public static final String MEMORY = "memoryMB";

    @Parameter( label = "Threads" )
    public int threads;
    public static final String THREADS = "threads";

    @Parameter( label = "Dataset ID" )
    public String dataSetID;
    public static final String DATASET_ID = "dataSetID";

    ImagePlus inputImage;
    DeepSegmentation deepSegmentation;
    String outputFileType;
    String outputFilesPrefix;

    private String outputFilenamesPrefix = "";

    // /Applications/Fiji.app/Contents/MacOS/ImageJ-macosx --run "Apply Classifier" "quitAfterRun='true',inputImagePath='/Users/tischer/Documents/fiji-plugin-deepSegmentation/src/test/resources/image-sequence/.*--W00016--P00003--.*',classifierFile='/Users/tischer/Documents/fiji-plugin-deepSegmentation/src/test/resources/transmission-cells-3d.classifier',outputModality='Save class probabilities as tiff files',outputDirectory='/Users/tischer/Documents/fiji-plugin-deepSegmentation/src/test/resources/image-sequence--classified'"

    // xvfb-run -a /g/almf/software/Fiji.app/ImageJ-linux64 --run "Apply Classifier" "quitAfterRun='true',inputImagePath='/g/cba/tischer/projects/transmission-3D-stitching-organoid-size-measurement--data/small-test-image-sequences/.*--W00016--P00004--.*',classifierFile='/g/cba/tischer/projects/transmission-3D-stitching-organoid-size-measurement--data/transmission-cells-3d.classifier',outputDirectory='/g/cba/tischer/projects/transmission-3D-stitching-organoid-size-measurement--data/small-test-image-sequences--classified/DataSet--W00016--P00004--',outputModality='Save class probabilities as tiff files'"

    public void run()
    {

        logService.info( "# " + PLUGIN_NAME );
        logCommandLineCall();

        logService.info( "Loading: " + inputImagePath );
        inputImage = IOUtils.loadImage( inputImagePath );
        inputImage.setTitle( dataSetID );
        outputFilesPrefix = dataSetID + "--";

        logService.info( "Applying classifier: " + classifierFile );
        applyClassifier();

        saveOutputImages();

        saveResultsTable();

        if ( quitAfterRun )  if ( quitAfterRun ) Commands.quitImageJ( logService );

    }

    private void saveOutputImages()
    {
        if ( outputModality.equals( IOUtils.SHOW_AS_ONE_IMAGE ) )
        {
            deepSegmentation.getResultImage().getWholeImageCopy().show();
        }

        if ( outputModality.equals( IOUtils.SAVE_AS_TIFF_STACKS ) )
        {
            saveProbabilitiesAsSeparateTiffFiles();
            outputFileType = ".tif";
        }

        if( outputModality.equals(  IOUtils.SAVE_AS_IMARIS ) )
        {
            saveProbabilitiesAsImarisFiles();
            outputFileType = ".ims";
        }
    }

    private void saveResultsTable()
    {
        ij.measure.ResultsTable resultsTable = new ij.measure.ResultsTable();
        resultsTable.incrementCounter();

        resultsTable.addValue( "DataSetID", dataSetID );

        resultsTable.addValue( "FileName_ApplyClassifier_InputImage", inputImagePath.getName() );
        resultsTable.addValue( "PathName_ApplyClassifier_InputImage", inputImagePath.getParent() );

        for ( String className : deepSegmentation.getClassNames() )
        {
            resultsTable.addValue( "FileName_ApplyClassifier_" + className, outputFilesPrefix + className + outputFileType );
            resultsTable.addValue( "PathName_ApplyClassifier_" + className, outputDirectory.getPath() );
        }

        resultsTable.save( outputDirectory.getPath() + "/" + dataSetID + "--ApplyClassifier.csv" );
    }

    /*
    private void saveProbabilitiesAsOneTiff()
    {
        ImagePlus result = deepSegmentation.getResultImage().getWholeImageCopy();
        String savingPath = "" + outputDirectory + File.separator + inputImage.getTitle() + "--classified.tif";
        logService.info( "Save results: " + savingPath);
        DeepSegmentation.logger.info("\n# Saving " + savingPath + "...");
        FileSaver fileSaver = new FileSaver( result );
        fileSaver.saveAsTiff( savingPath );
        DeepSegmentation.logger.info("...done.");
    }*/

    private void saveProbabilitiesAsSeparateTiffFiles()
    {
        deepSegmentation.getResultImage().saveClassesAsFiles( outputDirectory.getPath(), outputFilesPrefix, null, null, Utils.SEPARATE_TIFF_FILES );
    }

    private void saveProbabilitiesAsImarisFiles()
    {
        deepSegmentation.getResultImage().saveClassesAsFiles( outputDirectory.getPath(), outputFilesPrefix, null, null, Utils.SEPARATE_IMARIS );
    }

    private void applyClassifier( )
    {
        deepSegmentation = new DeepSegmentation( );
        deepSegmentation.setNumThreads( threads );
        deepSegmentation.setMaxMemory( memory * 1000000L ); // MB -> Byte
        deepSegmentation.setInputImage( inputImage );

        if ( outputModality.equals( IOUtils.SAVE_AS_TIFF_SLICES ) )
        {
            deepSegmentation.setResultImageDisk( outputDirectory.getAbsolutePath() );
        }
        else
        {
            deepSegmentation.setResultImageRAM();
        }

        deepSegmentation.loadClassifier( classifierFile.getAbsolutePath() );

        if ( inputImageInterval.equals( WHOLE_IMAGE ) )
        {
            deepSegmentation.applyClassifierWithTiling();
        }
        else
        {
            FinalInterval interval = getInterval();
            deepSegmentation.applyClassifierWithTiling( interval );
        }


    }

    private FinalInterval getInterval()
    {
        int[] minMaxXYZT = StringUtils.delimitedStringToIntegerArray( inputImageInterval, "," );
        long[] min = new long[5];
        long[] max = new long[5];

        int i = 0;
        for ( int d : XYZT )
        {
            min[ d ] = minMaxXYZT[ i++ ];
            max[ d ] = minMaxXYZT[ i++ ];
        }

        return new FinalInterval( min, max );
    }

    private void logCommandLineCall()
    {
        Map<String, Object> parameters = new HashMap<>( );
        parameters.put( IOUtils.INPUT_IMAGE_PATH, inputImagePath );
        parameters.put( IOUtils.OUTPUT_MODALITY, outputModality );
        parameters.put( OUTPUT_DIRECTORY, outputDirectory );
        parameters.put( QUIT_AFTER_RUN, quitAfterRun );
        parameters.put( CLASSIFIER_FILE, classifierFile );
        IJ.log( Commands.createImageJPluginCommandLineCall( "ImageJ", PLUGIN_NAME, parameters ) );
    }


}