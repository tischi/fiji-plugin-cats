package de.embl.cba.trainableDeepSegmentation.utils;

import de.embl.cba.cluster.JobFuture;
import de.embl.cba.utils.logging.Logger;

import java.util.ArrayList;

public class SlurmUtils
{
    public static void monitorJobProgress( ArrayList< JobFuture > jobFutures, Logger logger )
    {

        ArrayList< JobFuture > doneJobs = new ArrayList<>();
        int numResubmittedJobs = 0;

        while ( doneJobs.size() < jobFutures.size() )
        {
            for ( JobFuture jobFuture : jobFutures )
            {
                if ( jobFuture.isStarted() )
                {

                    String currentOutput = jobFuture.getOutput();

                    if  ( ! doneJobs.contains( jobFuture ) )
                    {
                        String[] currentOutputLines = currentOutput.split( "\n" );
                        String lastLine = currentOutputLines[ currentOutputLines.length - 1 ];
                        logger.info( "Current last line of job output: " + lastLine );
                    }

                    String resubmissionNeeded = jobFuture.needsResubmission();

                    if ( ! resubmissionNeeded.equals( JobFuture.NO_EVERYTHING_FINE ) )
                    {
                        logger.info( "# ERROR IN: " + jobFuture.getJobID() + ": " + resubmissionNeeded );
                        logger.info( "RESUBMITTING: " + jobFuture.getJobID() );
                        jobFuture.resubmit();
                        numResubmittedJobs++;
                    }
                    else if ( jobFuture.isDone() )
                    {
                        logger.info("Final and full job output:" );
                        logger.info( currentOutput );
                        doneJobs.add( jobFuture );

                        logger.info( " " );
                        logger.info( "# JOB STATI" );
                        logger.info( " " );
                        logger.info( "Total number of jobs: " + jobFutures.size() );
                        logger.info( "Finished jobs: " + doneJobs.size() );
                        logger.info( "Job resubmissions: " + numResubmittedJobs );
                        logger.info( " " );

                        if ( doneJobs.size() == jobFutures.size() )
                        {
                            break;
                        }
                    }
                }
                else
                {
                    logger.info( "Job " + jobFuture.getJobID() + " has not yet started." );
                }

                /*
                try
                {
                    HashMap< String, Object > output = jobFuture.get();
                } catch ( InterruptedException e )
                {
                    e.printStackTrace();
                } catch ( ExecutionException e )
                {
                    e.printStackTrace();
                }
                */

                try { Thread.sleep( 5000 ); } catch ( InterruptedException e ) { e.printStackTrace(); }
            }
        }

        logger.info( "All jobs finished." );

    }
}
