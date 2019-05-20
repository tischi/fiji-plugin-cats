package command;

import de.embl.cba.cats.ui.ApplyClassifierCommand;
import net.imagej.ImageJ;

public class RunApplyClassifierCommand
{
    public static void main( final String... args )
    {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        ij.command().run( ApplyClassifierCommand.class, true );
    }
}
