package org.aksw.lassie.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.aksw.lassie.kb.KnowledgeBase;
import org.aksw.lassie.kb.LocalKnowledgeBase;
import org.aksw.lassie.kb.RemoteKnowledgeBase;
import org.aksw.lassie.util.PrintUtils;
import org.apache.log4j.Logger;
import org.dllearner.algorithms.celoe.CELOE;
import org.dllearner.core.AbstractLearningProblem;
import org.dllearner.core.AbstractReasonerComponent;
import org.dllearner.core.ComponentInitException;
import org.dllearner.core.EvaluatedDescription;
import org.dllearner.core.KnowledgeSource;
import org.dllearner.core.owl.Description;
import org.dllearner.core.owl.Individual;
import org.dllearner.core.owl.NamedClass;
import org.dllearner.kb.OWLAPIOntology;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGenerator;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGeneratorImpl;
import org.dllearner.learningproblems.PosNegLPStandard;
import org.dllearner.learningproblems.PosOnlyLP;
import org.dllearner.reasoning.FastInstanceChecker;
import org.dllearner.utilities.datastructures.SetManipulation;
import org.dllearner.utilities.examples.AutomaticNegativeExampleFinderSPARQL2;
import org.dllearner.utilities.owl.OWLAPIDescriptionConvertVisitor;
import org.dllearner.utilities.owl.OWLClassExpressionToSPARQLConverter;
import org.dllearner.utilities.owl.OWLEntityTypeAdder;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;
import com.hp.hpl.jena.vocabulary.XSD;
import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

import de.uni_leipzig.simba.cache.Cache;
import de.uni_leipzig.simba.cache.MemoryCache;
import de.uni_leipzig.simba.data.Instance;
import de.uni_leipzig.simba.data.Mapping;
import de.uni_leipzig.simba.selfconfig.ComplexClassifier;
import de.uni_leipzig.simba.selfconfig.MeshBasedSelfConfigurator;
import de.uni_leipzig.simba.selfconfig.SimpleClassifier;

public class ExpressiveSchemaMappingGenerator {

	protected static final Logger logger = Logger.getLogger(ExpressiveSchemaMappingGenerator.class.getName());
	protected  Monitor mon;
	protected boolean posNegLearning = true;
	protected final boolean performCrossValidation = true;
	protected static final int maxNrOfIterations = 10;
	protected static final int coverageThreshold = 0;
	/** 
	 * The maximum number of positive examples, used for the SPARQL extraction
	 * and learning algorithm
	 */
	protected int maxNrOfPositiveExamples = 100;// 20;
	/**
	 * The maximum number of negative examples, used for the SPARQL extraction
	 * and learning algorithm
	 */
	protected int maxNrOfNegativeExamples = 100;//20;
	protected NamedClass currentClass;
	protected KnowledgeBase source;
	protected KnowledgeBase target;
	protected String linkingProperty = OWL.sameAs.getURI();
	protected int maxRecursionDepth = 2;
	/**
	 * LIMES Config
	 */
	static double coverage_LIMES = 1d;
	static double beta_LIMES = 1d;
	static String fmeasure_LIMES = "own";
	protected final int linkingMaxNrOfExamples_LIMES = 100;
	protected final int linkingMaxRecursionDepth_LIMES = 0;
	private String targetDomainNameSpace = "";
	

	/**
	 * @param domainOntolog the domainOntolog to set
	 */
	public void setTargetDomainNameSpace(String uri) {
		this.targetDomainNameSpace = uri;
	}

	public ExpressiveSchemaMappingGenerator() {
	}

	public ExpressiveSchemaMappingGenerator(KnowledgeBase source, KnowledgeBase target) {
		this(source, target, OWL.sameAs.getURI());
	}

	public ExpressiveSchemaMappingGenerator(KnowledgeBase source, KnowledgeBase target, String linkingProperty) {
		this.source = source;
		this.target = target;
		this.linkingProperty = linkingProperty;

		mon = MonitorFactory.getTimeMonitor("time");
		
		source.getReasoner().prepareSubsumptionHierarchy();
		target.getReasoner().prepareSubsumptionHierarchy();
	}

	public void run() {
		// get all classes C_i in source KB
		Set<NamedClass> sourceClasses = getClasses(source);

		// get all classes D_i in target KB
		Set<NamedClass> targetClasses = getClasses(target);

		run(sourceClasses, targetClasses);
	}

	public void run(Set<NamedClass> sourceClasses) {
		// get all classes D_i in target KB
		Set<NamedClass> targetClasses = getClasses(target);

		run(sourceClasses, targetClasses);
	}
	
	
	public Map<String, Object> run(Set<NamedClass> sourceClasses, Set<NamedClass> targetClasses) {
		Map<String, Object> result = new HashMap<String, Object>();
		
		//initially, the class expressions E_i in the target KB are the named classes D_i
		Collection<Description> targetClassExpressions = new TreeSet<Description>();
		targetClassExpressions.addAll(targetClasses);

		//perform the iterative schema matching
		Map<NamedClass, Description> mapping = new HashMap<NamedClass, Description>();
		Map<NamedClass, List<? extends EvaluatedDescription>> mappingTop10 = new HashMap<NamedClass, List<? extends EvaluatedDescription>>();
		int i = 1;
		double totalCoverage = 0;
		Map<Integer, Double> coverageMap = new TreeMap<Integer, Double>();
		do {
			//compute a set of links between each pair of class expressions (C_i, E_j), thus finally we get
			//a map from C_i to a set of instances in the target KB
			Multimap<NamedClass, String> links = performUnsupervisedLinking(sourceClasses, targetClassExpressions);
			result.put("posExamples", links);
			int j=1;
			//for each source class C_i, compute a mapping to a class expression in the target KB based on the links
			for (NamedClass sourceClass : sourceClasses) {
				currentClass = sourceClass;
				try {
					SortedSet<Individual> targetInstances = SetManipulation.stringToInd(links.get(sourceClass));

//					serializeCurrentObjects(j, sourceClass, targetInstances);
					
					List<? extends EvaluatedDescription> mappingList = computeMappings(targetInstances);
					mappingTop10.put(sourceClass, mappingList);
					EvaluatedDescription singleMapping = mappingList.get(0);
					
					mapping.put(sourceClass, singleMapping.getDescription());
				} catch (NonExistingLinksException e) {
					logger.warn(e.getMessage() + "Skipped learning.");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			//set the target class expressions
			targetClassExpressions = mapping.values();
			double newTotalCoverage = computeCoverage(mapping, source);

			if((newTotalCoverage-totalCoverage) <= coverageThreshold){
				break;
			}
			
			totalCoverage = newTotalCoverage;
			coverageMap.put(i++, totalCoverage);
			
		} while (i <= maxNrOfIterations);

		result.put("mapping", mapping);
		result.put("mappingTop10", mappingTop10);
		result.put("coverage", coverageMap);
		return result;
	}

	/**
	 * @param j
	 * @param sourceClass
	 * @param targetInstances
	 * @throws IOException
	 * @throws FileNotFoundException
	 * @author sherif
	 */
	public void serializeCurrentObjects(int j, NamedClass sourceClass,
			SortedSet<Individual> targetInstances) throws IOException,
			FileNotFoundException {
		ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("sourceClass"+j+".ser"));
		out.writeObject(sourceClass);
		
		out = new ObjectOutputStream(new FileOutputStream("targetInstances"+j+".ser"));
		out.writeObject(targetInstances);
		j++;
//		System.exit(1);
	}

	double computeCoverage(Map<NamedClass, Description> mapping, KnowledgeBase kb){

		double totalCoverage = 0;

		for (Entry<NamedClass, Description> entry : mapping.entrySet()) {
			NamedClass source = entry.getKey();
			Description target = entry.getValue();

			SortedSet<Individual> sourceInstances = getInstances(source, kb);
			SortedSet<Individual> targetInstances = getInstances(target, kb);

			double coverage = computeJaccardSimilarity(sourceInstances, targetInstances);
			totalCoverage += coverage;
		}

		totalCoverage /= mapping.size();
		return totalCoverage;
	}

	double computeJaccardSimilarity(Set<Individual> sourceInstances, Set<Individual> targetInstances){
		// JaccardDistance = 2*|C_i n D_j|/(|C_i|+|D_j|)
		SetView<Individual> intersection = Sets.intersection(sourceInstances, targetInstances);
		SetView<Individual> union = Sets.union(sourceInstances, targetInstances);
//		return (double) (intersection.size())/union.size(); 
		return 2*((double)intersection.size())/(sourceInstances.size()+targetInstances.size());
	}

	/**
	 * Run LIMES to generate owl:sameAs links
	 *
	 * @param sourceClasses
	 * @param targetClasses
	 */
	private Multimap<NamedClass, String> performUnsupervisedLinking(Set<NamedClass> sourceClasses, Collection<Description> targetClasses) {
		logger.info("Computing links...");
		//compute the Concise Bounded Description(CBD) for each instance
		//in each source class C_i, thus creating a model for each class
		Map<NamedClass, Model> sourceClassToModel = new HashMap<NamedClass, Model>();
		for (NamedClass sourceClass : sourceClasses) {
			//get all instances of C_i
			SortedSet<Individual> sourceInstances = getSourceInstances(sourceClass);
			sourceInstances = SetManipulation.stableShrinkInd(sourceInstances, linkingMaxNrOfExamples_LIMES);

			//get the fragment describing the instances of C_i
			logger.info("Computing fragment...");
			Model sourceFragment = getFragment(sourceInstances, source, linkingMaxRecursionDepth_LIMES);
			removeNonLiteralStatements(sourceFragment);
			logger.info("...got " + sourceFragment.size() + " triples.");
			sourceClassToModel.put(sourceClass, sourceFragment);
		}

		//compute the Concise Bounded Description(CBD) for each instance
		//in each each target class expression D_i, thus creating a model for each class expression
		Map<Description, Model> targetClassExpressionToModel = new HashMap<Description, Model>();
		for (Description targetClass : targetClasses) {
			// get all instances of D_i
			SortedSet<Individual> targetInstances = getTargetInstances(targetClass);
			targetInstances = SetManipulation.stableShrinkInd(targetInstances, linkingMaxNrOfExamples_LIMES);

			// get the fragment describing the instances of D_i
			logger.info("Computing fragment...");
			Model targetFragment = getFragment(targetInstances, target, linkingMaxRecursionDepth_LIMES);
			removeNonLiteralStatements(targetFragment);
			logger.info("...got " + targetFragment.size() + " triples.");
			targetClassExpressionToModel.put(targetClass, targetFragment);
		}

		Multimap<NamedClass, String> map = HashMultimap.create();

		//for each C_i
		for (Entry<NamedClass, Model> entry : sourceClassToModel.entrySet()) {
			NamedClass sourceClass = entry.getKey();
			Model sourceClassModel = entry.getValue();

			//for each D_i
			for (Entry<Description, Model> entry2 : targetClassExpressionToModel.entrySet()) {
				Description targetClassExpression = entry2.getKey();
				Model targetClassExpressionModel = entry2.getValue();

				Mapping result = getDeterministicUnsupervisedMappings(getCache(sourceClassModel), getCache(targetClassExpressionModel));
				
				for (Entry<String, HashMap<String, Double>> mappingEntry : result.map.entrySet()) {
					String key = mappingEntry.getKey();
					HashMap<String, Double> value = mappingEntry.getValue();
					map.put(sourceClass, value.keySet().iterator().next());
				}
			}
		}
		return map;
	}

	private void removeNonLiteralStatements(Model m){
		StmtIterator iterator = m.listStatements();
		List<Statement> statements2Remove = new ArrayList<Statement>();
		while(iterator.hasNext()){
			Statement st = iterator.next();
			if(!st.getObject().isLiteral()){
				statements2Remove.add(st);
			}
		}
		m.remove(statements2Remove);
	}

	public Set<String> getAllProperties(Cache c) {
		//    	logger.info("Get all properties...");
		if (c.size() > 0) {
			HashSet<String> props = new HashSet<String>();
			for (Instance i : c.getAllInstances()) {
				props.addAll(i.getAllProperties());
			}
			return props;
		} else {
			return new HashSet<String>();
		}
	}

	/**
	 * Computes initial mappings
	 *
	 */
	public Mapping getDeterministicUnsupervisedMappings(Cache source, Cache target) {
		logger.info("Source size = "+source.getAllUris().size());
		logger.info("Target size = "+target.getAllUris().size());

		MeshBasedSelfConfigurator bsc = new MeshBasedSelfConfigurator(source, target, coverage_LIMES, beta_LIMES);
		bsc.setMeasure(fmeasure_LIMES);
		//        Set<String> sProperties = getAllProperties(source);
		//        Set<String> tProperties = getAllProperties(target);
		List<SimpleClassifier> cp = bsc.getBestInitialClassifiers();
		if(cp.size() == 0) 
		{
			logger.warn("No property mapping found");
			return new Mapping();
		}
		//        List<SimpleClassifier> cp = new ArrayList<SimpleClassifier>();
		//        for (String property : sProperties) {
		//            for(String )
		//            //cp.add(new SimpleClassifier("jaccard", 1.0, property, property));
		//            cp.add(new SimpleClassifier("levenshtein", 1.0, property, property));
		//            cp.add(new SimpleClassifier("trigrams", 1.0, property, property));
		//        }
		ComplexClassifier cc = bsc.getZoomedHillTop(5, 5, cp);
		Mapping map = Mapping.getBestOneToOneMappings(cc.mapping);
		logger.info("Mapping size is " + map.getNumberofMappings());
		logger.info("Pseudo F-measure is " + cc.fMeasure);
		return map;
	}

	public Cache getCache(Model m) {
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
	
	public EvaluatedDescription computeMapping(SortedSet<Individual> positiveExamples) throws NonExistingLinksException {
		return computeMappings(positiveExamples).get(0);
	}

	public List<? extends EvaluatedDescription> computeMappings(Description targetClassExpression) throws NonExistingLinksException {
		SortedSet<Individual> targetInstances = getTargetInstances(targetClassExpression);
		return computeMappings(targetInstances);
	}

	public List<? extends EvaluatedDescription> computeMappings(SortedSet<Individual> positiveExamples) throws NonExistingLinksException {
		//if there are no links to the target KB, then we can skip learning
		if (positiveExamples.isEmpty()) {
			throw new NonExistingLinksException();
		} else {
			//compute a mapping
			//get a sample of the positive examples
			SortedSet<Individual> positiveExamplesSample = SetManipulation.stableShrinkInd(positiveExamples, maxNrOfPositiveExamples);

			//starting from the positive examples, we first extract the fragment for them
			logger.info("Extracting fragment for positive examples...");
			mon.start();
			Model positiveFragment = getFragment(positiveExamplesSample, target);
			mon.stop();
			logger.info("...got " + positiveFragment.size() + " triples in " + mon.getLastValue() + "ms.");
//			for (Individual ind : positiveExamplesSample) {
//				System.out.println(ResultSetFormatter.asText(
//						com.hp.hpl.jena.query.QueryExecutionFactory.create("SELECT * WHERE {<" + ind.getName() + "> a ?o.}", positiveFragment).execSelect()));
//			}

			//compute the negative examples
			logger.info("Computing negative examples...");
			MonitorFactory.getTimeMonitor("negative examples").start();
			AutomaticNegativeExampleFinderSPARQL2 negativeExampleFinder = new AutomaticNegativeExampleFinderSPARQL2(target.getReasoner(), target.getNamespace());
			SortedSet<Individual> negativeExamples = negativeExampleFinder.getNegativeExamples(positiveExamples, maxNrOfNegativeExamples);
			negativeExamples.removeAll(positiveExamples);
			MonitorFactory.getTimeMonitor("negative examples").stop();
			logger.info("Found " + negativeExamples.size() + " negative examples in " + MonitorFactory.getTimeMonitor("negative examples").getTotal() + "ms.");

			//get a sample of the negative examples
			SortedSet<Individual> negativeExamplesSample = SetManipulation.stableShrinkInd(negativeExamples, maxNrOfNegativeExamples);
			//create fragment for negative examples
			logger.info("Extracting fragment for negative examples...");
			mon.start();
			Model negativeFragment = getFragment(negativeExamplesSample, target);
			mon.stop();
			logger.info("...got " + negativeFragment.size() + " triples in " + mon.getLastValue() + "ms.");

			logger.info("Learning input:");
			logger.info("Positive examples: " + positiveExamplesSample.size() + " with " + positiveFragment.size() + " triples, e.g. \n" + print(positiveExamplesSample, 3));
			logger.info("Negative examples: " + negativeExamplesSample.size() + " with " + negativeFragment.size() + " triples, e.g. \n" + print(negativeExamplesSample, 3));

			//create fragment consisting of both
			OntModel fullFragment = ModelFactory.createOntologyModel(OntModelSpec.RDFS_MEM);
			fullFragment.add(positiveFragment);
			fullFragment.add(negativeFragment);
			filter(fullFragment, target.getNamespace());
			
			//learn the class expressions
			return learnClassExpressions(fullFragment, positiveExamplesSample, negativeExamplesSample);
		}
	}
	
	private List<? extends EvaluatedDescription> learnClassExpressions(Model model, SortedSet<Individual> positiveExamples, SortedSet<Individual> negativeExamples) {
		try {
			cleanUpModel(model);
			OWLEntityTypeAdder.addEntityTypes(model);
			KnowledgeSource ks = convert(model);

			//initialize the reasoner
			logger.info("Initializing reasoner...");
			AbstractReasonerComponent rc = new FastInstanceChecker(ks);
			rc.init();
			logger.info("Done.");

			//initialize the learning problem
			logger.info("Initializing learning problem...");
			AbstractLearningProblem lp;
			if (!negativeExamples.isEmpty()) {
				lp = new PosNegLPStandard(rc, positiveExamples, negativeExamples);
			} else {
				lp = new PosOnlyLP(rc, positiveExamples);
			}
			lp.init();
			logger.info("Done.");

			//initialize the learning algorithm
			logger.info("Initializing learning algorithm...");
			CELOE la = new CELOE(lp, rc);
			la.setMaxExecutionTimeInSeconds(10);
			la.setNoisePercentage(25);
			la.init();
			logger.info("Done.");

			//apply the learning algorithm
			logger.info("Running learning algorithm...");
			la.start();
			logger.info(la.getCurrentlyBestEvaluatedDescription());

			return la.getCurrentlyBestEvaluatedDescriptions(10);
		} catch (ComponentInitException e) {
			logger.error(e);
		}
		return null;
	}



	private String print(Collection<Individual> individuals, int n){
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (Individual individual : individuals) {
			sb.append(individual.getName() + ",");
		}
		sb.append("...");
		return sb.toString();
	}

	/**
	 * Return all instances of the given class in the source KB.
	 *
	 * @param cls
	 * @return
	 */
	private SortedSet<Individual> getSourceInstances(NamedClass cls) {
		logger.info("Retrieving instances of class " + cls + "...");
		mon.start();
		SortedSet<Individual> instances = new TreeSet<Individual>();
		String query = String.format("SELECT DISTINCT ?s WHERE {?s a <%s>}", cls.getName());
		ResultSet rs = source.executeSelect(query);
		QuerySolution qs;
		while (rs.hasNext()) {
			qs = rs.next();
			instances.add(new Individual(qs.getResource("s").getURI()));
		}
		mon.stop();
		logger.info("...found " + instances.size() + " instances in " + mon.getLastValue() + "ms.");
		return instances;
	}

	/**
	 * Return all instances which are (assumed to be) contained in the target
	 * KB. Here we should apply a namespace filter on the URIs such that we get
	 * only instances which are really contained in the target KB.
	 *
	 * @param cls
	 * @return
	 */
	private SortedSet<Individual> getTargetInstances(Description desc) {
		return getInstances(desc, target);
	}

	private SortedSet<Individual> getInstances(Description desc, KnowledgeBase kb) {
		logger.info("Retrieving instances of class expression " + desc + "...");
		mon.start();
		SortedSet<Individual> instances = new TreeSet<Individual>();
		OWLClassExpressionToSPARQLConverter converter = new OWLClassExpressionToSPARQLConverter();
		OWLClassExpression classExpression = OWLAPIDescriptionConvertVisitor.getOWLClassExpression(desc);
		Query query = converter.asQuery("?x", classExpression);
		ResultSet rs = kb.executeSelect(query.toString());
		QuerySolution qs;
		while (rs.hasNext()) {
			qs = rs.next();
			instances.add(new Individual(qs.getResource("x").getURI()));
		}
		mon.stop();
		logger.info("...found " + instances.size() + " instances in " + mon.getLastValue() + "ms.");
		return instances;
	}

	/**
	 * Return all instances which are (assumed to be) contained in the target
	 * KB. Here we should apply a namespace filter on the URIs such that we get
	 * only instances which are really contained in the target KB.
	 *
	 * @param cls
	 * @return
	 */
	private SortedSet<Individual> getTargetInstances(NamedClass cls) {
		logger.info("Retrieving instances to which instances of class " + cls + " are linked to via property " + linkingProperty + "...");
		mon.start();
		SortedSet<Individual> instances = new TreeSet<Individual>();
		String query = String.format("SELECT DISTINCT ?o WHERE {?s a <%s>. ?s <%s> ?o. FILTER(REGEX(?o,'^%s'))}", cls.getName(), linkingProperty, target.getNamespace());
		ResultSet rs = source.executeSelect(query);
		QuerySolution qs;
		while (rs.hasNext()) {
			qs = rs.next();
			instances.add(new Individual(qs.getResource("o").getURI()));
		}
		mon.stop();
		logger.info("...found " + instances.size() + " instances in " + mon.getLastValue() + "ms.");
		return instances;
	}

	private KnowledgeSource convert(Model model) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			model.write(baos, "TURTLE", null);
			OWLOntologyManager man = OWLManager.createOWLOntologyManager();
			OWLOntology ontology = man.loadOntologyFromOntologyDocument(new ByteArrayInputStream(baos.toByteArray()));
			return new OWLAPIOntology(ontology);
		} catch (Exception e) {
			e.printStackTrace();
			try {model.write(new FileOutputStream("error.ttl"), "TURTLE", null);
				model.write(new FileOutputStream("errors/" + PrintUtils.prettyPrint(currentClass) + "_conversion_error.ttl"), "TURTLE", null);
			} catch (FileNotFoundException e1) {
				e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * Computes a fragment containing hopefully useful information about the
	 * resources.
	 *
	 * @param ind
	 */
	private Model getFragment(SortedSet<Individual> individuals, KnowledgeBase kb) {
		return getFragment(individuals, kb, maxRecursionDepth);
	}

	/**
	 * Computes a fragment containing hopefully useful information about the
	 * resources.
	 *
	 * @param ind
	 */
	private Model getFragment(SortedSet<Individual> individuals, KnowledgeBase kb, int recursionDepth) {
		//        OntModel fullFragment = ModelFactory.createOntologyModel();
		Model fullFragment = ModelFactory.createDefaultModel();
		int i = 1;
		Model fragment;
		for (Individual ind : individuals) {
			fragment = getFragment(ind, kb, recursionDepth);
			System.out.println(ind + ": " + fragment.size() + " triples");
			//			logger.info(i++  + "/" + individuals.size());
			fullFragment.add(fragment);
		}
		//        cleanUpModel(fullFragment);
		return fullFragment;
	}

	/**
	 * Computes a fragment containing hopefully useful information about the
	 * resource.
	 *
	 * @param ind
	 */
	private Model getFragment(Individual ind, KnowledgeBase kb) {
		return getFragment(ind, kb, maxRecursionDepth);
	}

	/**
	 * Computes a fragment containing hopefully useful information about the
	 * resource.
	 *
	 * @param ind
	 */
	private Model getFragment(Individual ind, KnowledgeBase kb, int recursionDepth) {
		logger.debug("Loading fragment for " + ind.getName());
		ConciseBoundedDescriptionGenerator cbdGen;
		if (kb.isRemote()) {
			cbdGen = new ConciseBoundedDescriptionGeneratorImpl(((RemoteKnowledgeBase) kb).getEndpoint(), ((RemoteKnowledgeBase) kb).getCache().getCacheDirectory());
		} else {
			cbdGen = new ConciseBoundedDescriptionGeneratorImpl(((LocalKnowledgeBase) kb).getModel());
		}
		Model cbd = cbdGen.getConciseBoundedDescription(ind.getName(), 1);
		logger.debug("Got " + cbd.size() + " triples.");
		return cbd;
	}

	private void cleanUpModel(Model model) {
		// filter out triples with String literals, as therein often occur
		// some syntax errors and they are not relevant for learning
		List<Statement> statementsToRemove = new ArrayList<Statement>();
		for (Iterator<Statement> iter = model.listStatements().toList().iterator(); iter.hasNext();) {
			Statement st = iter.next();
			RDFNode object = st.getObject();
			if (object.isLiteral()) {
				// statementsToRemove.add(st);
				Literal lit = object.asLiteral();
				if (lit.getDatatype() == null || lit.getDatatype().equals(XSD.xstring)) {
					st.changeObject("shortened", "en");
				}
			}
			//remove statements like <x a owl:Class>
			if (st.getPredicate().equals(RDF.type)) {
				if (object.equals(RDFS.Class.asNode()) || object.equals(OWL.Class.asNode()) || object.equals(RDFS.Literal.asNode())
						|| object.equals(RDFS.Resource)) {
					statementsToRemove.add(st);
				}
			}
		}
		
		model.remove(statementsToRemove);
	}
	
	/**
	 * Filter triples which are not relevant based on the given knowledge base namespace.
	 * @param model
	 * @param namespace
	 */
	private void filter(Model model, String namespace){
		List<Statement> statementsToRemove = new ArrayList<Statement>();
		for (Iterator<Statement> iter = model.listStatements().toList().iterator(); iter.hasNext();) {
			Statement st = iter.next();
			Property predicate = st.getPredicate();
			if(predicate.equals(RDF.type)){
				if(!st.getObject().asResource().getURI().startsWith(namespace)){
					statementsToRemove.add(st);
				}
			} else if(!predicate.equals(RDFS.subClassOf) && !predicate.equals(OWL.sameAs) && !predicate.asResource().getURI().startsWith(namespace)){
				statementsToRemove.add(st);
			}
		}
		model.remove(statementsToRemove);
	}

	private Set<NamedClass> getClasses(KnowledgeBase kb) {
		Set<NamedClass> classes = new HashSet<NamedClass>();

		//get all OWL classes
		String query = String.format("SELECT ?type WHERE {?type a <%s>.}", OWL.Class.getURI());
		ResultSet rs = kb.executeSelect(query);
		QuerySolution qs;
		while (rs.hasNext()) {
			qs = rs.next();
			if (qs.get("type").isURIResource()) {
				classes.add(new NamedClass(qs.get("type").asResource().getURI()));
			}
		}

		//fallback: check for ?s a ?type where ?type is not asserted to owl:Class
		if (classes.isEmpty()) {
			query = "SELECT ?type WHERE {?s a ?type.}";
			rs = kb.executeSelect(query);
			while (rs.hasNext()) {
				qs = rs.next();
				if (qs.get("type").isURIResource()) {
					classes.add(new NamedClass(qs.get("type").asResource().getURI()));
				}
			}
		}
		return classes;
	}

	private SortedSet<Individual> getRelatedIndividualsNamespaceAware(KnowledgeBase kb, NamedClass nc, String targetNamespace) {
		SortedSet<Individual> relatedIndividuals = new TreeSet<Individual>();
		//get all individuals o which are connected to individuals s belonging to class nc
		//		String query = String.format("SELECT ?o WHERE {?s a <%s>. ?s <http://www.w3.org/2002/07/owl#sameAs> ?o. FILTER(REGEX(STR(?o),'%s'))}", nc.getName(), targetNamespace);
		//		ResultSet rs = executeSelect(kb, query);
		//		QuerySolution qs;
		//		while(rs.hasNext()){
		//			qs = rs.next();
		//			RDFNode object = qs.get("o");
		//			if(object.isURIResource()){
		//				
		//				String uri = object.asResource().getURI();
		//				//workaround for World Factbook - should be removed later
		//				uri = uri.replace("http://www4.wiwiss.fu-berlin.de/factbook/resource/", "http://wifo5-03.informatik.uni-mannheim.de/factbook/resource/");
		//				//workaround for OpenCyc - should be removed later
		//				uri = uri.replace("http://sw.cyc.com", "http://sw.opencyc.org");
		//				
		//				relatedIndividuals.add(new Individual(uri));
		//			}
		//		}
		return relatedIndividuals;
	}
}
