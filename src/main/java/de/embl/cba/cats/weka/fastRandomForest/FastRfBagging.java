/*
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

/*
 *    FastRfBagging.java
 *    Copyright (C) 1999 University of Waikato, Hamilton, NZ (original code,
 *      Bagging.java )
 *    Copyright (C) 2013 Fran Supek (adapted code)
 */

package de.embl.cba.cats.weka.fastRandomForest;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.embl.cba.cats.CATS;
import de.embl.cba.utils.logging.IJLazySwingLogger;
import de.embl.cba.utils.logging.Logger;
import ij.IJ;
import weka.classifiers.Classifier;
import weka.classifiers.RandomizableIteratedSingleClassifierEnhancer;
import weka.core.AdditionalMeasureProducer;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Option;
import weka.core.RevisionUtils;
import weka.core.Utils;
import weka.core.WeightedInstancesHandler;


/**
 * Based on the "weka.classifiers.meta.Bagging" class, revision 1.39,
 * by Kirkby, Frank and Trigg, with modifications:
 * <ul>
 * <li>Instead of Instances, produces DataCaches; consequently, FastRfBagging
 * is compatible only with FastRandomTree as base classifier</li>
 * <li>The function for resampling the data is removed; this is a responsibility
 * of the DataCache objects3DPopulation now</li>
 * <li>Not a TechnicalInformationHandler anymore</li>
 * <li>The classifiers are trained in separate "tasks" which are handled by
 * an ExecutorService (the FixedThreadPool) which runs the tasks in
 * more numWorkers in parallel. If the number of numWorkers is not specified,
 * it will be set automatically to the available number of cores.</li>
 * <li>Estimating the out-of-bag (OOB) error is also multithreaded, using
 * the VotesCollector class</li>
 * <li>OOB estimation in Weka's Bagging is one tree - one vote. In FastRF 0.97
 * onwards, some trees will have a heavier weight in the overall vote
 * depending on the averaged weights of instances that ended in the specific
 * leaf.</li>
 * </ul>
 * <p>
 * This class should be used only from within the FastRandomForest classifier.
 * </p>
 *
 * @author Eibe Frank (eibe@cs.waikato.ac.nz) - original code
 * @author Len Trigg (len@reeltwo.com) - original code
 * @author Richard Kirkby (rkirkby@cs.waikato.ac.nz) - original code
 * @author Fran Supek (fran.supek[AT]irb.hr) - adapted code
 * @version $Revision: 0.99$
 */
class FastRfBagging extends RandomizableIteratedSingleClassifierEnhancer
  implements WeightedInstancesHandler, AdditionalMeasureProducer {

  /**
   * for serialization
   */
  private static final long serialVersionUID = -505879962237199702L;

  /**
   * Bagging method. Produces DataCache objects3DPopulation with bootstrap samples of
   * the original data, and feeds them to the base classifier (which can only
   * be a FastRandomTree).
   *
   * @param data         The instances set to be used for generating the
   *                     bagged classifier.
   * @param numThreads   The number of simultaneous numWorkers to use for
   *                     computation. Pass zero (0) for autodetection.
   * @param motherForest A reference to the FastRandomForest object that
   *                     invoked this.
   *
   * @throws Exception if the classifier could not be built successfully
   */
  public void buildClassifier(Instances data, int numThreads,
                              FastRandomForest motherForest) throws Exception {

    // can classifier handle the vals?
    getCapabilities().testWithFail(data);

    // remove instances with missing class
    // data = new Instances(data); // TISCHI commented this
    // data.deleteWithMissingClass(); // TISCHI commented this

    if (!(m_Classifier instanceof FastRandomTree))
      throw new IllegalArgumentException("The FastRfBagging class accepts " +
        "only FastRandomTree as its base classifier.");

    /* We fill the m_Classifiers array by creating lots of trees with new()
     * because this is much faster than using serialization to deep-copy the
     * one tree in m_Classifier - this is what the super.buildClassifier(data)
     * normally does. */
    m_Classifiers = new Classifier[m_NumIterations];
    for (int i = 0; i < m_Classifiers.length; i++) {
      FastRandomTree curTree = new FastRandomTree();
      // all parameters for instances will be looked up in the motherForest (classifierMaxDepth, k_Value)
      curTree.m_MotherForest = motherForest;
      // 0.99: reference to these arrays will getInstancesAndMetadata passed down all nodes so the array can be re-used
      // 0.99: this array is of size two as now all splits are binary - even categorical ones
      curTree.tempProps = new double[2]; 
      curTree.tempDists = new double[2][]; 
      curTree.tempDists[0] = new double[data.numClasses()];
      curTree.tempDists[1] = new double[data.numClasses()];
      curTree.tempDistsOther = new double[2][]; 
      curTree.tempDistsOther[0] = new double[data.numClasses()];
      curTree.tempDistsOther[1] = new double[data.numClasses()];
      m_Classifiers[i] = curTree;
    }

    // this was SLOW.. takes approx 1/2 time as instances the forest afterwards (!!!)
    // super.buildClassifier(data);

    //if (m_CalcOutOfBag && (m_BagSizePercent != 100)) {
    //  throw new IllegalArgumentException("Bag size needs to be 100% if " +
    //    "out-of-bag error is to be calculated!");
    //}


    // sorting is performed inside this constructor
    IJ.log( "\nCreating instances data cache..." );
    DataCache myData = new DataCache( data );
    IJ.log( "...done." );

    int bagSize = data.numInstances() * m_BagSizePercent / 100;
    Random random = new Random(m_Seed);

    boolean[][] inBag = new boolean[m_Classifiers.length][];

    // thread management
    ExecutorService threadPool = Executors.newFixedThreadPool(
      numThreads > 0 ? numThreads : Runtime.getRuntime().availableProcessors());

    List<Future<?>> futures =
      new ArrayList<Future<?>>(m_Classifiers.length);

    try {

      for ( int treeIdx = 0; treeIdx < m_Classifiers.length; treeIdx++ ) {

        // create the in-bag dataset (and be sure to remember what's in bag)
        // for computing the out-of-bag error later

        DataCache bagData;

        // Tischi
        if ( motherForest.labelIds != null )
        {
          CATS.logger.progress("Training data resampling:", "Tree " + (treeIdx + 1) + "/" + m_Classifiers.length );
          bagData = myData.resampleBalancingLabels( m_BagSizePercent, motherForest.labelIds, random );
        }
        else
        {
          bagData = myData.resample( bagSize, random );
        }

        bagData.reusableRandomGenerator = bagData.getRandomNumberGenerator( random.nextInt() );
        inBag[ treeIdx ] = bagData.inBag; // store later for OOB error calculation

        // build the classifier
        if ( m_Classifiers[ treeIdx ] instanceof FastRandomTree )
        {
          FastRandomTree aTree = (FastRandomTree) m_Classifiers[ treeIdx ];
          aTree.data = bagData;

          Future<?> future = threadPool.submit( aTree );
          futures.add( future );
        }
        else
        {
          throw new IllegalArgumentException("The FastRfBagging class accepts " +
            "only FastRandomTree as its base classifier.");
        }

      }

      // Tischi
      Logger logger = new IJLazySwingLogger();
      long start = System.currentTimeMillis();

      // make sure all trees have been trained before proceeding
      for (int treeIdx = 0; treeIdx < m_Classifiers.length; treeIdx++)
      {
        futures.get( treeIdx ).get();
        logger.progress( "Building trees...", null, start, treeIdx+1, m_Classifiers.length); // Tischi
      }

      // calc OOB error?
      if (getCalcOutOfBag() || getComputeImportances()) {
        //m_OutOfBagError = computeOOBError(data, inBag, threadPool);
        m_OutOfBagError = computeOOBError( myData, inBag, threadPool );
      } else {
        m_OutOfBagError = 0;
      }

      //calc feature importances
      m_FeatureImportances = null;
      //m_FeatureNames = null;
      if (getComputeImportances()) {
        m_FeatureImportances = new double[data.numAttributes()];
        ///m_FeatureNames = new String[data.numAttributes()];
        //Instances dataCopy = new Instances(data); //To scramble
        //int[] permutation = FastRfUtils.randomPermutation(data.numInstances(), random);
        for (int j = 0; j < data.numAttributes(); j++) {
          if (j != data.classIndex()) {
            //double sError = computeOOBError(FastRfUtils.scramble(data, dataCopy, j, permutation), inBag, threadPool);
            //double sError = computeOOBError(data, inBag, threadPool, j, 0);
            float[] unscrambled = myData.scrambleOneAttribute(j, random);
            double sError = computeOOBError(myData, inBag, threadPool);
            myData.vals[j] = unscrambled; // restore the original state
            m_FeatureImportances[j] = sError - m_OutOfBagError;
          }
          //m_FeatureNames[j] = data.attribute(j).name();
        }
      }

      // reassign the attribute ids, because next time this classifier will be used,
      // only the attributes in the window will be present.
      if ( motherForest.attIndicesWindowExternallySet  )
      {
          IJ.log("\nClassifier trained with attribute subset => reindexing all tree nodes...");

        ArrayList<Integer> attIndicesList = new ArrayList<Integer>();

        int[] window = motherForest.getAttIndicesWindow();

        for (int i : window)
        {
          attIndicesList.add(i);
        }

        for (int i = 0; i < m_Classifiers.length; i++) {
          FastRandomTree tree = (FastRandomTree) m_Classifiers[ i ];
          tree.reassignAttributes( attIndicesList );
        }

        IJ.log("...done.");


      }

      threadPool.shutdown();

    }
    finally {
      threadPool.shutdownNow();
    }
  }

  /**
   * Compute the out-of-bag error for a set of instances.
   *
   * @param data       the instances
   * @param inBag      numTrees x numInstances indicating out-of-bag instances
   * @param threadPool the pool of numWorkers
   *
   * @return the oob error
   */
  private double computeOOBError(Instances data,
                                 boolean[][] inBag,
                                 ExecutorService threadPool) throws InterruptedException, ExecutionException {

    boolean numeric = data.classAttribute().isNumeric();

    List<Future<Double>> votes =
      new ArrayList<Future<Double>>(data.numInstances());
    for (int i = 0; i < data.numInstances(); i++) {
      VotesCollector aCollector = new VotesCollector(m_Classifiers, i, data, inBag);
      votes.add(threadPool.submit(aCollector));
    }


    double outOfBagCount = 0.0;
    double errorSum = 0.0;

    for (int i = 0; i < data.numInstances(); i++) {

      double vote = votes.get(i).get();

      // error for instance
      outOfBagCount += data.instance(i).weight();
      if (numeric) {
        errorSum += StrictMath.abs(vote - data.instance(i).classValue()) * data.instance(i).weight();
      } else {
        if (vote != data.instance(i).classValue())
          errorSum += data.instance(i).weight();
      }

    }

    return errorSum / outOfBagCount;
  }



  /**
   * Compute the out-of-bag error on the instances in a DataCache. This must
   * be the datacache used for instances the FastRandomForest (this is not
   * checked in the function!).
   *
   * @param data       the instances (as a DataCache)
   * @param inBag      numTrees x numInstances indicating out-of-bag instances
   * @param threadPool the pool of numWorkers
   *
   * @return the oob error
   */
  private double computeOOBError( DataCache data,
                                 boolean[][] inBag,
                                 ExecutorService threadPool ) throws InterruptedException, ExecutionException {


    List<Future<Double>> votes =
      new ArrayList<Future<Double>>(data.numInstances);
    for (int i = 0; i < data.numInstances; i++) {
      VotesCollectorDataCache aCollector = new VotesCollectorDataCache(m_Classifiers, i, data, inBag);
      votes.add(threadPool.submit(aCollector));
    }

    double outOfBagCount = 0.0;
    double errorSum = 0.0;

    for (int i = 0; i < data.numInstances; i++) {

      double vote = votes.get(i).get();

      if ( vote >= 0)
      {
        // error for instance
        outOfBagCount += data.instWeights[i];
        if ((int) vote != data.instClassValues[i])
        {
          errorSum += data.instWeights[i];
        }
      }

    }

    return errorSum / outOfBagCount;
    
  }
  
  
  
  
  ////////////////////////////
  // Feature importances stuff
  ////////////////////////////

  /**
   * The value of the features importances.
   */
  private double[] m_FeatureImportances;
  /**
   * Whether compute the importances or not.
   */
  private boolean m_computeImportances = true;

  /**
   * @return compute feature importances?
   */
  public boolean getComputeImportances() {
    return m_computeImportances;
  }

  /**
   * @param computeImportances compute feature importances?
   */
  public void setComputeImportances(boolean computeImportances) {
    m_computeImportances = computeImportances;
  }

  /**
   * @return unnormalized feature importances
   */
  public double[] getFeatureImportances() {
    return m_FeatureImportances;
  }
  
  /** Used when displaying feature importances. */
  //private String[] m_FeatureNames; 
  
  /** Available only if feature importances have been computed. */
  //public String[] getAllFeatureNames() {
  //  return m_FeatureNames;
  //}
  

  ////////////////////////////
  // /Feature importances stuff
  ////////////////////////////

  /**
   * Not supported.
   */
  @Override
  public void buildClassifier(Instances data) throws Exception {
    throw new Exception("FastRfBagging can be built only from within a FastRandomForest.");
  }

  /**
   * The size of each bag sample, as a percentage of the instances size
   */
  protected int m_BagSizePercent = 100;

  /**
   * Whether to calculate the out of bag error
   */
  protected boolean m_CalcOutOfBag = true;

  /**
   * The out of bag error that has been calculated
   */
  protected double m_OutOfBagError;

  /**
   * Constructor.
   */
  public FastRfBagging() {

    m_Classifier = new FastRandomTree();
  }


  /**
   * Returns a string describing classifier
   *
   * @return a description suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String globalInfo() {

    return "Class for bagging a classifier to reduce variance. Can do classification "
      + "and regression depending on the base learner. \n\n";
  }


  /**
   * String describing default classifier.
   *
   * @return the default classifier classname
   */
  @Override
  protected String defaultClassifierString() {
    return "hr.irb.fastRandomForest.FastRfTree";
  }


  /**
   * Returns an enumeration describing the available options.
   *
   * @return an enumeration of all the available options.
   */
  @Override
  public Enumeration listOptions() {

    Vector newVector = new Vector(2);

    newVector.addElement(new Option(
      "\tSize of each bag, as a percentage of the\n"
        + "\tinstances set size. (default 100)",
      "P", 1, "-P"));
    newVector.addElement(new Option(
      "\tCalculate the out of bag error.",
      "O", 0, "-O"));

    Enumeration enu = super.listOptions();
    while (enu.hasMoreElements()) {
      newVector.addElement(enu.nextElement());
    }
    return newVector.elements();
  }


  /**
   * Parses a given list of options.
   *
   * <!-- options-start -->
   * <p>
   * Valid options are:
   * </p>
   * <pre> -P
   *  Size of each bag, as a percentage of the
   *  instances set size. (default 100)</pre>
   * <pre> -O
   *  Calculate the out of bag error.</pre>
   * <pre> -S &lt;num&gt;
   *  Random number seed.
   *  (default 1)</pre>
   * <pre> -I &lt;num&gt;
   *  Number of iterations.
   *  (default 10)</pre>
   * <pre> -D
   *  If set, classifier is run in debug mode and
   *  may output additional info to the console</pre>
   * <pre> -W
   *  Full name of base classifier.
   *  (default: fastRandomForest.classifiers.FastRandomTree)</pre>
   * <!-- options-end -->
   * <p>
   * Options after -- are passed to the designated classifier.
   * </p>
   *
   * @param options the list of options as an array of strings
   * @throws Exception if an option is not supported
   */
  @Override
  public void setOptions(String[] options) throws Exception {

    String bagSize = Utils.getOption('P', options);
    if (bagSize.length() != 0) {
      setBagSizePercent(Integer.parseInt(bagSize));
    } else {
      setBagSizePercent(100);
    }

    setCalcOutOfBag(Utils.getFlag('O', options));

    super.setOptions(options);
  }

  /**
   * Gets the current featureSettings of the Classifier.
   *
   * @return an array of strings suitable for passing to setOptions
   */
  @Override
  public String[] getOptions() {


    String[] superOptions = super.getOptions();
    String[] options = new String[superOptions.length + 3];

    int current = 0;
    options[current++] = "-P";
    options[current++] = String.valueOf(getBagSizePercent());

    if (getCalcOutOfBag()) {
      options[current++] = "-O";
    }

    System.arraycopy(superOptions, 0, options, current,
            superOptions.length);

    current += superOptions.length;
    while (current < options.length) {
      options[current++] = "";
    }
    return options;
  }

  /**
   * Returns the tip text for this property
   *
   * @return tip text for this property suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String bagSizePercentTipText() {
    return "Size of each bag, as a percentage of the instances set size.";
  }

  /**
   * Gets the size of each bag, as a percentage of the instances set size.
   *
   * @return the bag size, as a percentage.
   */
  public int getBagSizePercent() {

    return m_BagSizePercent;
  }

  /**
   * Sets the size of each bag, as a percentage of the instances set size.
   *
   * @param newBagSizePercent the bag size, as a percentage.
   */
  public void setBagSizePercent(int newBagSizePercent) {

    m_BagSizePercent = newBagSizePercent;
  }

  /**
   * Returns the tip text for this property
   *
   * @return tip text for this property suitable for
   *         displaying in the explorer/experimenter gui
   */
  public String calcOutOfBagTipText() {
    return "Whether the out-of-bag error is calculated.";
  }

  /**
   * Set whether the out of bag error is calculated.
   *
   * @param calcOutOfBag whether to calculate the out of bag error
   */
  public void setCalcOutOfBag(boolean calcOutOfBag) {

    m_CalcOutOfBag = calcOutOfBag;
  }

  /**
   * Get whether the out of bag error is calculated.
   *
   * @return whether the out of bag error is calculated
   */
  public boolean getCalcOutOfBag() {

    return m_CalcOutOfBag;
  }

  /**
   * Gets the out of bag error that was calculated as the classifier
   * was built.
   *
   * @return the out of bag error
   */
  public double measureOutOfBagError() {

    return m_OutOfBagError;
  }

  /**
   * Returns an enumeration of the additional measure names.
   *
   * @return an enumeration of the measure names
   */
  public Enumeration enumerateMeasures() {

    Vector newVector = new Vector(1);
    newVector.addElement("measureOutOfBagError");
    return newVector.elements();
  }

  /**
   * Returns the value of the named measure.
   *
   * @param additionalMeasureName the name of the measure to query for its value
   *
   * @return the value of the named measure
   *
   * @throws IllegalArgumentException if the named measure is not supported
   */
  public double getMeasure(String additionalMeasureName) {

    if (additionalMeasureName.equalsIgnoreCase("measureOutOfBagError")) {
      return measureOutOfBagError();
    } else {
      throw new IllegalArgumentException(additionalMeasureName
        + " not supported (Bagging)");
    }
  }


  /**
   * Calculates the class membership probabilities for the given test
   * instance.
   *
   * @param instance the instance to be classified
   *
   * @return preedicted class probability distribution
   *
   * @throws Exception if distribution can't be computed successfully
   */
  @Override
  public double[] distributionForInstance( Instance instance ) throws Exception {

    double[] sums = new double[instance.numClasses()], newProbs;

    for (int i = 0; i < m_NumIterations; i++) {
      if (instance.classAttribute().isNumeric()) {
        sums[0] += m_Classifiers[i].classifyInstance(instance);
      } else {
        newProbs = m_Classifiers[i].distributionForInstance(instance);
        for (int j = 0; j < newProbs.length; j++)
          sums[j] += newProbs[j];
      }
    }

    if (instance.classAttribute().isNumeric()) {
      sums[0] /= (double) m_NumIterations;
      return sums;
    } else if (Utils.eq(Utils.sum(sums), 0)) {
      return sums;
    } else {
      Utils.normalize(sums);
      return sums;
    }

  }


  // Tischi
  public double[] distributionForInstanceUntilSignificant( Instance instance,
                                                           double requiredSignificance ) throws Exception {

    double[] sums = new double[instance.numClasses()], newProbs;
    double sum = 0.0, p0 = 0.0, p1 = 0.0;
    double[] currentProbs = new double[instance.numClasses()];
    double diffSquared;
    double semSquared;
    double significanceSquared;
    int c0 = 0, c1 = 0;

    double requiredSignificanceSquared = requiredSignificance * requiredSignificance;

    for (int i = 0; i < m_NumIterations; i++) {
      if (instance.classAttribute().isNumeric()) {
        sums[0] += m_Classifiers[i].classifyInstance(instance);
      } else {
        newProbs = m_Classifiers[i].distributionForInstance(instance);
        for (int j = 0; j < newProbs.length; j++)
        {
          sums[j] += newProbs[j];
        }
      }

      if ( ((requiredSignificance > 0) && ( (i > 0) && ( (i % 10) == 0 ) ))
              || (i == m_NumIterations - 1) )
      {


        // normalise current probabilities to sum up to 1
        // - and also determine the two most likely probabilities
        //
        sum = 0.0;

        for ( int c = 0; c < sums.length; c++ )
        {
          sum += sums[c];
        }

        for ( int c = 0; c < sums.length; c++ )
        {
          currentProbs[c] = sums[c] / sum;

          if ( c == 0 )
          {
            p0 = currentProbs[ 0 ];
            p1 = 0.0;
            c0 = 0;
            c1 = 0;
          }
          else if ( currentProbs[ c ] > p0 )
          {
              p1 = p0;
              p0 = currentProbs[ c ];
              c1 = c0;
              c0 = c;
          }
          else if ( currentProbs[ c ] > p1 )
          {
              p1 = currentProbs[ c ];
              c1 = c;
          }
        }

        if ( requiredSignificance > 0 )
        {

          // Evaluate statistical significance of difference
          // between most and second-most likely class.
          // Return resultImagePlus is significance is reached, even
          // before all trees have been evaluated.

          if ( p0 == 1.0 )
          {
            // all trees agree
            return ( new double[]{ c0, c1, p0, p1, i + 1 } );
          }
          else
          {// Evaluate statistical significance of difference
            // between most and second-most likely class.
            // Minimally evaluate 10 tress.
            // compute sort of a statistical significance:
            // significance = diff / sem
            diffSquared = ( p0 - p1 ) * ( p0 - p1 );
            semSquared = p0 * ( 1.0 - p0 ) / ( i + 1 );

            significanceSquared = diffSquared / semSquared;

            if ( ( significanceSquared > requiredSignificanceSquared ) )
            {
              return ( new double[]{ c0, c1, p0, p1, i + 1 } );
            }
          }
        }
      }
    }

    // all trees were evaluated without the required significance being reached
    //
    return ( new double[] { c0, c1, p0, p1, m_NumIterations} );

  }


  /**
   * Returns description of the bagged classifier.
   *
   * @return description of the bagged classifier as a string
   */
  @Override
  public String toString() {

    if (m_Classifiers == null) {
      return "FastRfBagging: No model built yet.";
    }
    StringBuffer text = new StringBuffer();
    text.append("All the base classifiers: \n\n");
    for (int i = 0; i < m_Classifiers.length; i++)
      text.append(m_Classifiers[i].toString() + "\n\n");

    if (m_CalcOutOfBag) {
      text.append("Out of bag error: "
        + Utils.doubleToString(m_OutOfBagError, 4)
        + "\n\n");
    }

    return text.toString();
  }

  /**
   * Main method for testing this class.
   *
   * @param argv the options
   */
  public static void main(String[] argv) {
    runClassifier(new FastRfBagging(), argv);
  }

  public String getRevision() {
    return RevisionUtils.extract("$Revision: 0.99$");
  }
}