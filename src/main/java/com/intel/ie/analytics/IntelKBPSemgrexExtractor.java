package com.intel.ie.analytics;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.semgrex.SemgrexBatchParser;
import edu.stanford.nlp.semgraph.semgrex.SemgrexMatcher;
import edu.stanford.nlp.semgraph.semgrex.SemgrexPattern;
import edu.stanford.nlp.simple.Sentence;
import edu.stanford.nlp.util.ArgumentParser;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.logging.Redwood;
import edu.stanford.nlp.util.logging.RedwoodConfiguration;

import java.io.*;
import java.util.*;

/**
 * A tokensregex extractor for KBP.
 *
 * @author Gabor Angeli
 */
public class IntelKBPSemgrexExtractor implements IntelKBPRelationExtractor {
    protected final Redwood.RedwoodChannels logger = Redwood.channels(IntelKBPSemgrexExtractor.class);

    @ArgumentParser.Option(name="dir", gloss="The tokensregex directory")
    public static String DIR = IntelConfig.KBP_SEMGREX_DIR;

    @ArgumentParser.Option(name="test", gloss="The dataset to test on")
    public static File TEST_FILE = new File("test.conll");

    @ArgumentParser.Option(name="predictions", gloss="Dump model predictions to this file")
    public static Optional<String> PREDICTIONS = Optional.empty();

    private final Map<RelationType, Collection<SemgrexPattern> > rules = new HashMap<>();


    public IntelKBPSemgrexExtractor(String semgrexdir) throws IOException {
        logger.log("Creating SemgrexRegexExtractor");
        // Create extractors
        for (RelationType rel : RelationType.values()) {
            String filename = semgrexdir + File.separator + rel.canonicalName.replace("/", "SLASH") + ".rules";
            if (IOUtils.existsInClasspathOrFileSystem(filename)) {

                List<SemgrexPattern> rulesforrel = SemgrexBatchParser.compileStream(IOUtils.getInputStreamFromURLOrClasspathOrFileSystem(filename));
                logger.log("Read " + rulesforrel.size() + " rules from " + filename + " for relation " + rel);
                rules.put(rel, rulesforrel);
            }
        }
    }


    @Override
    public Pair<String, Double> classify(KBPInput input) {
        for (RelationType rel : RelationType.values()) {

            if (rules.containsKey(rel) &&
                    rel.entityType == input.subjectType &&
                    rel.validNamedEntityLabels.contains(input.objectType)) {
                Collection<SemgrexPattern> rulesForRel = rules.get(rel);
                CoreMap sentence = input.sentence.asCoreMap(Sentence::nerTags, Sentence::dependencyGraph);
                boolean matches
                        = matches(sentence, rulesForRel, input,
                        sentence.get(SemanticGraphCoreAnnotations.EnhancedPlusPlusDependenciesAnnotation.class)) ||
                        matches(sentence, rulesForRel, input,
                                sentence.get(SemanticGraphCoreAnnotations.AlternativeDependenciesAnnotation.class));
                if (matches) {
                    //logger.log("MATCH for " + rel +  ". " + sentence: + sentence + " with rules for  " + rel);
                    return Pair.makePair(rel.canonicalName, 1.0);
                }
            }
        }

        return Pair.makePair(NO_RELATION, 1.0);
    }


    /**
     * Returns whether any of the given patterns match this tree.
     */
    private boolean matches(CoreMap sentence, Collection<SemgrexPattern> rulesForRel,
                            KBPInput input, SemanticGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return false;
        }

        List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
        for (int i : input.subjectSpan) {
            if ("O".equals(tokens.get(i).ner())) {
                tokens.get(i).setNER(input.subjectType.name);
            }
        }
        for (int i : input.objectSpan) {
            if ("O".equals(tokens.get(i).ner())) {
                tokens.get(i).setNER(input.objectType.name);
            }
        }

        for (SemgrexPattern p : rulesForRel) {

            try {
                SemgrexMatcher n = p.matcher(graph);
                while (n.find()) {
                    return true;
//                    IndexedWord entity = n.getNode("entity");
//                    IndexedWord slot = n.getNode("slot");
//                    boolean hasSubject = entity.index() >= input.subjectSpan.start() + 1 && entity.index() <= input.subjectSpan.end();
//                    boolean hasObject  = slot.index() >= input.objectSpan.start() + 1 && slot.index() <= input.objectSpan.end();
//
//                    if (hasSubject && hasObject) {
//                        return true;
//                    }
                }
            } catch (Exception e) {
                //Happens when graph has no roots
                return false;
            }
        }
        return false;
    }


    public static void main(String[] args) throws IOException {
        RedwoodConfiguration.standard().apply();  // Disable SLF4J crap.
        ArgumentParser.fillOptions(IntelKBPSemgrexExtractor.class, args);
        IntelKBPSemgrexExtractor extractor = new IntelKBPSemgrexExtractor(DIR);
        List<Pair<KBPInput, String>> testExamples = DatasetUtils.readDataset(TEST_FILE);

        extractor.computeAccuracy(testExamples.stream(), PREDICTIONS.map(x -> {
            try {
                return "stdout".equalsIgnoreCase(x) ? System.out : new PrintStream(new FileOutputStream(x));
            } catch (IOException e) {
                throw new RuntimeIOException(e);
            }
        }));
    }

}
