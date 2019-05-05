import de.embl.cba.cats.ui.CATSCommand;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImageJ;

public class RunCatsCommand
{

    public static void main( final String... args )
    {

        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        ImagePlus imp = IJ.openImage(
                RunCatsCommand.class.getResource(
                        "blobs/input/blobs_00.tif" ).getFile() );

		imp.show();

        ij.command().run( CATSCommand.class, true );
    }


}
