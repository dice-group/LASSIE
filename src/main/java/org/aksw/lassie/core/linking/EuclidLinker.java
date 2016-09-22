package org.aksw.lassie.core.linking;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.aksw.lassie.core.SimpleClassifierComparator;
import org.aksw.lassie.kb.KnowledgeBase;
import org.aksw.lassie.result.LassieResultRecorder;
import org.apache.log4j.Logger;
import org.dllearner.utilities.datastructures.SetManipulation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLIndividual;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.vocabulary.OWL;
import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

import de.uni_leipzig.simba.cache.Cache;
import de.uni_leipzig.simba.cache.MemoryCache;
import de.uni_leipzig.simba.data.Mapping;
import de.uni_leipzig.simba.multilinker.MappingMath;
import de.uni_leipzig.simba.selfconfig.ComplexClassifier;
import de.uni_leipzig.simba.selfconfig.MeshBasedSelfConfigurator;
import de.uni_leipzig.simba.selfconfig.ReferencePseudoMeasures;
import de.uni_leipzig.simba.selfconfig.SimpleClassifier;

public class EuclidLinker extends AbstractUnsupervisedLinker{
    protected static final Logger logger = Logger.getLogger(EuclidLinker.class);

    protected Monitor mon;

    // Euclid Configurations
    protected static final int numberOfDimensions = 7;
    static double coverage_LIMES = 0.8;
    static double beta_LIMES = 1d;
    static String fmeasure_LIMES = "own";
    protected final int linkingMaxNrOfExamples_LIMES = 100;
    protected final int linkingMaxRecursionDepth_LIMES = 0;
    private int numberOfLinkingIterations = 5;
    protected String linkingProperty = OWL.sameAs.getURI();
    protected Map<OWLClass, Map<OWLClassExpression, Mapping>> mappingResults = new HashMap<OWLClass, Map<OWLClassExpression, Mapping>>();

    protected KnowledgeBase sourceKB;
    protected KnowledgeBase targetKB;

    private int iterationNr = 0;

    //result recording
    LassieResultRecorder resultRecorder;

    public EuclidLinker(KnowledgeBase sourceKB, KnowledgeBase targetKB, String linkingProperty, LassieResultRecorder resultRecorder){
        this.sourceKB = sourceKB;
        this.targetKB = targetKB; 

        this.linkingProperty = linkingProperty;

        mon = MonitorFactory.getTimeMonitor("time");

        this.resultRecorder = resultRecorder;
    }

    @Override
    public Multimap<OWLClass, String> link(Set<OWLClass> sourceClasses, Collection<OWLClassExpression> targetClasses) {
        logger.info("Computing links...");
        logger.info("Source classes: " + sourceClasses);
        logger.info("Target classes: " + targetClasses);
        //compute the Concise Bounded Description(CBD) for each instance
        //in each source class C_i, thus create a model for each class
        Map<OWLClass, Model> sourceClassToModel = new HashMap<OWLClass, Model>();
        for (OWLClass sourceClass : sourceClasses) {
            //get all instances of C_i
            SortedSet<OWLIndividual> sourceInstances = getSourceInstances(sourceClass);
            //            sourceInstances = SetManipulation.stableShrinkInd(sourceInstances, linkingMaxNrOfExamples_LIMES);

            //get the fragment describing the instances of C_i
            logger.debug("Computing fragment...");
            Model sourceFragment = sourceKB.getFragment(sourceInstances, linkingMaxRecursionDepth_LIMES);
            removeNonStringLiteralStatements(sourceFragment);
            logger.debug("...got " + sourceFragment.size() + " triples.");
            sourceClassToModel.put(sourceClass, sourceFragment);
        }

        //compute the Concise Bounded Description(CBD) for each instance
        //in each each target class expression D_i, thus create a model for each class expression
        Map<OWLClassExpression, Model> targetClassExpressionToModel = new HashMap<OWLClassExpression, Model>();
        for (OWLClassExpression targetClass : targetClasses) {
            // get all instances of D_i
            SortedSet<OWLIndividual> targetInstances = targetKB.getInstances(targetClass);
            //            targetInstances = SetManipulation.stableShrinkInd(targetInstances, linkingMaxNrOfExamples_LIMES);

            // get the fragment describing the instances of D_i
            logger.debug("Computing fragment...");
            Model targetFragment = targetKB.getFragment(targetInstances, linkingMaxRecursionDepth_LIMES);
            removeNonStringLiteralStatements(targetFragment);
            logger.debug("...got " + targetFragment.size() + " triples.");
            targetClassExpressionToModel.put(targetClass, targetFragment);
        }

        Multimap<OWLClass, String> map = HashMultimap.create();

        //for each C_i
        for (Entry<OWLClass, Model> entry : sourceClassToModel.entrySet()) {
            OWLClass sourceClass = entry.getKey();
            Model sourceClassModel = entry.getValue();

            //TODO TEST
            try {
                sourceClassModel.write(new FileWriter("/home/sherif/JavaProjects/LASSIE/tmp/" + sourceClass.toStringID().substring(sourceClass.toStringID().lastIndexOf("/")+1) + ".ttl"), "TTL");
            } catch (IOException e) {
                e.printStackTrace();
            }

            //          currentClass = sourceClass; 

            Cache cache = modelToCache(sourceClassModel);

            //for each D_i
            for (Entry<OWLClassExpression, Model> entry2 : targetClassExpressionToModel.entrySet()) {
                OWLClassExpression targetClassExpression = entry2.getKey();
                Model targetClassExpressionModel = entry2.getValue();

                logger.debug("Computing links between " + sourceClass + " and " + targetClassExpression + "...");

                Cache cache2 = modelToCache(targetClassExpressionModel);

                //TODO TEST
                try {
                    targetClassExpressionModel.write(new FileWriter("/home/sherif/JavaProjects/LASSIE/tmp/_" +  targetClassExpression.toString().substring(targetClassExpression.toString().lastIndexOf("/")+1)+ ".ttl"), "TTL");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                Mapping result = null;

                //buffers the mapping results and only carries out a computation if the mapping results are unknown
                if (mappingResults.containsKey(sourceClass)) {
                    if (mappingResults.get(sourceClass).containsKey(targetClassExpression)) {
                        result = mappingResults.get(sourceClass).get(targetClassExpression);
                    }
                }

                if (result == null) {
                    result = getDeterministicUnsupervisedMappings(cache, cache2, sourceClass);
                    if (!mappingResults.containsKey(sourceClass)) {
                        mappingResults.put(sourceClass, new HashMap<OWLClassExpression, Mapping>());
                    }
                    mappingResults.get(sourceClass).put(targetClassExpression, result);
                }

                //Keep record of the real F-Measures
                if(result.size > 0){
                    double f = MappingMath.computeFMeasure(result, cache2.size());
                    resultRecorder.setFMeasure(f, iterationNr, sourceClass);
                    resultRecorder.setInstanceMapping(result, iterationNr, sourceClass);
                }

                for (Entry<String, HashMap<String, Double>> mappingEntry : result.map.entrySet()) {
                    HashMap<String, Double> value = mappingEntry.getValue();
                    map.put(sourceClass, value.keySet().iterator().next());
                }
            }
        }
        return map;
    }


    @Override
    public Multimap<OWLClass, String> linkMultiThreaded(Set<OWLClass> sourceClasses, Collection<OWLClassExpression> targetClasses) {
        logger.info("Computing links...");
        logger.info("Source classes: " + sourceClasses);
        logger.info("Target classes: " + targetClasses);
        //compute the Concise Bounded Description(CBD) for each instance
        //in each source class C_i, thus creating a model for each class
        Map<OWLClass, Model> sourceClassToModel = new HashMap<OWLClass, Model>();
        for (OWLClass sourceClass : sourceClasses) {
            //get all instances of C_i
            SortedSet<OWLIndividual> sourceInstances = getSourceInstances(sourceClass);
            sourceInstances = SetManipulation.stableShrinkInd(sourceInstances, linkingMaxNrOfExamples_LIMES);

            //get the fragment describing the instances of C_i
            logger.debug("Computing fragment...");
            Model sourceFragment = sourceKB.getFragment(sourceInstances, linkingMaxRecursionDepth_LIMES);
            removeNonStringLiteralStatements(sourceFragment);
            logger.debug("...got " + sourceFragment.size() + " triples.");
            sourceClassToModel.put(sourceClass, sourceFragment);
        }

        //compute the Concise Bounded Description(CBD) for each instance
        //in each each target class expression D_i, thus creating a model for each class expression
        Map<OWLClassExpression, Model> targetClassExpressionToModel = new HashMap<OWLClassExpression, Model>();
        for (OWLClassExpression targetClass : targetClasses) {
            // get all instances of D_i
            SortedSet<OWLIndividual> targetInstances = targetKB.getInstances(targetClass);
            //          targetInstances = SetManipulation.stableShrinkInd(targetInstances, linkingMaxNrOfExamples_LIMES);
            //          ArrayList<OWLIndividual> l = new ArrayList<OWLIndividual>(targetInstances);
            //          Collections.reverse(l);
            //          targetInstances = new TreeSet<OWLIndividual>(l.subList(0, Math.min(100, targetInstances.size())));

            // get the fragment describing the instances of D_i
            logger.debug("Computing fragment...");
            Model targetFragment = targetKB.getFragment(targetInstances, linkingMaxRecursionDepth_LIMES);
            removeNonStringLiteralStatements(targetFragment);
            logger.debug("...got " + targetFragment.size() + " triples.");
            targetClassExpressionToModel.put(targetClass, targetFragment);
        }

        final Multimap<OWLClass, String> map = HashMultimap.create();

        ExecutorService threadPool = Executors.newFixedThreadPool(7);
        List<Future<LinkingResult>> list = new ArrayList<Future<LinkingResult>>();

        //for each C_i
        for (Entry<OWLClass, Model> entry : sourceClassToModel.entrySet()) {
            final OWLClass sourceClass = entry.getKey();
            Model sourceClassModel = entry.getValue();

            final Cache sourceCache = modelToCache(sourceClassModel);

            //for each D_i
            for (Entry<OWLClassExpression, Model> entry2 : targetClassExpressionToModel.entrySet()) {
                final OWLClassExpression targetClassExpression = entry2.getKey();
                Model targetClassExpressionModel = entry2.getValue();

                logger.debug("Computing links between " + sourceClass + " and " + targetClassExpression + "...");

                final Cache targetCache = modelToCache(targetClassExpressionModel);

                //buffers the mapping results and only carries out a computation if the mapping results are unknown
                if (mappingResults.containsKey(sourceClass) && mappingResults.get(sourceClass).containsKey(targetClassExpression)) {
                    Mapping result = mappingResults.get(sourceClass).get(targetClassExpression);
                    for (Entry<String, HashMap<String, Double>> mappingEntry : result.map.entrySet()) {
                        HashMap<String, Double> value = mappingEntry.getValue();
                        map.put(sourceClass, value.keySet().iterator().next());
                    }
                } else {
                    list.add(threadPool.submit(new EuclidTask(sourceClass, targetClassExpression, sourceCache, targetCache)));
                }
            }
        }

        try {
            threadPool.shutdown();
            threadPool.awaitTermination(5, TimeUnit.HOURS);
            for (Future<LinkingResult> future : list) {
                try {
                    LinkingResult result = future.get();
                    if (!mappingResults.containsKey(result.source)) {
                        mappingResults.put(result.source, new HashMap<OWLClassExpression, Mapping>());
                    }
                    mappingResults.get(result.source).put(result.target, result.mapping);
                    for (Entry<String, HashMap<String, Double>> mappingEntry : result.mapping.map.entrySet()) {
                        HashMap<String, Double> value = mappingEntry.getValue();
                        map.put(result.source, value.keySet().iterator().next());
                    }
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return map;
    }



    /**
     * Return all instances of the given class in the source KB.
     *
     * @param cls
     * @return
     */
    private SortedSet<OWLIndividual> getSourceInstances(OWLClass cls) {
        logger.debug("Retrieving instances of class " + cls + "...");
        mon.start();
        SortedSet<OWLIndividual> instances = new TreeSet<>();
        String query = String.format("SELECT DISTINCT ?s WHERE {?s a <%s>}", cls.toStringID());
        ResultSet rs = sourceKB.executeSelect(query);
        QuerySolution qs;
        while (rs.hasNext()) {
            qs = rs.next();
            instances.add(owlDataFactory.getOWLNamedIndividual(IRI.create(qs.getResource("s").getURI())));
        }
        mon.stop();
        logger.debug("...found " + instances.size() + " instances in " + mon.getLastValue() + "ms.");
        return instances;
    }

    private void removeNonStringLiteralStatements(Model m) {
        StmtIterator iterator = m.listStatements();
        List<Statement> statements2Remove = new ArrayList<Statement>();
        while (iterator.hasNext()) {
            Statement st = iterator.next();
            if (!st.getObject().isLiteral()
                    || !(st.getObject().asLiteral().getDatatype() == null || 
                    st.getObject().asLiteral().getDatatypeURI().equals(XSDDatatype.XSDstring.getURI()))){
                statements2Remove.add(st);
            }
        }
        m.remove(statements2Remove);
    }


    /**
     * Computes initial mappings
     * @param sourceClass 
     *
     */
    public Mapping getDeterministicUnsupervisedMappings(Cache source, Cache target, OWLClass sourceClass) {
        logger.info("Source size = " + source.getAllUris().size());
        logger.info("Target size = " + target.getAllUris().size());

        MeshBasedSelfConfigurator bsc = new MeshBasedSelfConfigurator(source, target, coverage_LIMES, beta_LIMES);
        //ensures that only the threshold 1.0 is tested. Can be set to a lower value
        //default is 0.3
        bsc.MIN_THRESHOLD = 0.9;
        bsc.setMeasure(fmeasure_LIMES);
        Set<String> measure =  new HashSet<String>();
        measure.add("trigrams");
        //      measure.add("euclidean");
        //      measure.add("levenshtein");
        //      measure.add("jaccard");
        List<SimpleClassifier> cp = bsc.getBestInitialClassifiers(measure);

        if (cp.isEmpty()) {
            logger.warn("No property mapping found");
            return new Mapping();
        }
        //get subset of best initial classifiers

        Collections.sort(cp, new SimpleClassifierComparator());
        Collections.reverse(cp);
        if(cp.size() > numberOfDimensions)
            cp = cp.subList(0, numberOfDimensions);

        ComplexClassifier cc = bsc.getZoomedHillTop(5, numberOfLinkingIterations, cp);
        Mapping map = Mapping.getBestOneToOneMappings(cc.mapping);
        logger.debug("Classifier: " + cc.classifiers);
        resultRecorder.setClassifier(cc.classifiers, iterationNr, sourceClass);
        resultRecorder.setPFMeasure(new ReferencePseudoMeasures().getPseudoFMeasure(source.getAllUris(), target.getAllUris(), map, 1.0),
                iterationNr, sourceClass);

        return map;
    }


    /**
     * Convert Jena Model to LIMES Cache
     * @param m
     * @return
     */
    public Cache modelToCache(Model m) {
        Cache c = new MemoryCache();
        for (Statement s : m.listStatements().toList()) {
            if (s.getObject().isResource()) {
                c.addTriple(s.getSubject().getURI(), s.getPredicate().getURI(), s.getObject().asResource().getURI());
            } else {
                c.addTriple(s.getSubject().getURI(), s.getPredicate().getURI(), s.getObject().asLiteral().getLexicalForm());
            }
        }
        return c;
    }

    //  /**
    //  * Return all instances which are (assumed to be) contained in the target
    //  * KB. Here we should apply a namespace filter on the URIs such that we get
    //  * only instances which are really contained in the target KB.
    //  *
    //  * @param cls
    //  * @return
    //  */
    // private SortedSet<OWLIndividual> getTargetInstances(OWLClass cls) {
    //     logger.trace("Retrieving instances to which instances of class " + cls + " are linked to via property " + linkingProperty + "...");
    //     mon.start();
    //     SortedSet<OWLIndividual> instances = new TreeSet<>();
    //     String query = String.format("SELECT DISTINCT ?o WHERE {?s a <%s>. ?s <%s> ?o. FILTER(REGEX(?o,'^%s'))}", cls.toStringID(), linkingProperty, targetKB.getNamespace());
    //     ResultSet rs = sourceKB.executeSelect(query);
    //     QuerySolution qs;
    //     while (rs.hasNext()) {
    //         qs = rs.next();
    //         instances.add(owlDataFactory.getOWLNamedIndividual(IRI.create(qs.getResource("o").getURI())));
    //
    //     }
    //     mon.stop();
    //     logger.trace("...found " + instances.size() + " instances in " + mon.getLastValue() + "ms.");
    //     return instances;
    // }
}