package de.embl.cba.trainableDeepSegmentation.classification;

import de.embl.cba.trainableDeepSegmentation.instances.InstancesAndMetadata;
import weka.classifiers.Classifier;

public class ClassifierInstancesMetadata
{
    public Classifier classifier;
    public InstancesAndMetadata instancesAndMetadata;

    public ClassifierInstancesMetadata( )
    {
        this.classifier = null;
        this.instancesAndMetadata = null;
    }

    public ClassifierInstancesMetadata( Classifier classifier,
                                        InstancesAndMetadata instancesAndMetadata)
    {
        this.classifier = classifier;
        this.instancesAndMetadata = instancesAndMetadata;
    }


}