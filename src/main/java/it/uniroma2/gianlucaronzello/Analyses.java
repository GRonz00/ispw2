package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.Main.Result;
import it.uniroma2.gianlucaronzello.utils.DatasetPaths;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ConverterUtils;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Analyses {
    private final String project;
    private final int lastRelease;
    private Instances testing;
    private Instances training;
    private static final Logger logger = Logger.getLogger("Perform analysis");
    public Analyses(String project, int lastRelease){
        this.project = project;
        this.lastRelease = lastRelease;
    }
    public List<Result> performAnalysis()  {
        List<Result> Results = new ArrayList<>();
        // No Feature Selection, No Balancing
        for (AnalysisVariables.Classifiers classifierType : AnalysisVariables.Classifiers.values()) {
            this.training = loadInstance(project, lastRelease, "training");
            this.testing = loadInstance(project, lastRelease, "testing");
            Classifier classifier = selectClassifier(classifierType);
            Evaluation evaluation = analyze(classifier);
            Result Result;
            if (evaluation != null) {
                Result = generateResult(evaluation, classifierType, AnalysisVariables.FeatureSelection.NONE, AnalysisVariables.Sampling.NONE);
                Results.add(Result);
            }

        }
        // Feature Selection, Balancing
        for (AnalysisVariables.Sampling sampling : AnalysisVariables.Sampling.values()) {
            for (AnalysisVariables.Classifiers classifierType : AnalysisVariables.Classifiers.values()) {
                this.training = loadInstance(project, lastRelease, "training");
                this.testing = loadInstance(project, lastRelease, "testing");
                AttributeSelection filter = new AttributeSelection();
                CfsSubsetEval evaluator = new CfsSubsetEval();
                BestFirst search = new BestFirst();
                filter.setEvaluator(evaluator);
                filter.setSearch(search);
                try {
                    filter.setInputFormat(training);
                    training = Filter.useFilter(training, filter);
                    testing = Filter.useFilter(testing, filter);
                } catch (Exception e) {
                    logger.info("errore nell'applicare best fit");
                }
                applySampling(sampling);
                Classifier classifier = selectClassifier(classifierType);
                Evaluation evaluation = analyze(classifier);
                Result Result;
                if (evaluation != null) {
                    Result = generateResult(evaluation, classifierType, AnalysisVariables.FeatureSelection.BEST_FIRST, sampling);
                    Results.add(Result);
                }

            }
        }
        return Results;
    }
    private Instances loadInstance(String project, int testingRelease, String instanceType) {
        try {
            Path path = DatasetPaths.fromProject(project)
                    .resolve("arff")
                    .resolve(String.format("%s-%d.arff", instanceType, testingRelease));
            Instances instance = ConverterUtils.DataSource.read(path.toString());
            if (instance.classIndex() == -1)
                instance.setClassIndex(instance.numAttributes() - 1);
            return instance;
        } catch (Exception e) {
            logger.info("errore nel caricamento arff");
            return null;
        }
    }
    private Classifier selectClassifier(AnalysisVariables.Classifiers classifierType) {
        return switch (classifierType) {
            case RANDOM_FOREST -> new RandomForest();
            case NAIVE_BAYES -> new NaiveBayes();
            case IBK -> new IBk();
        };

    }
    private Evaluation analyze(Classifier classifier)  {
        try {
            classifier.buildClassifier(training);
        } catch (Exception e) {
            logger.info("errore nella costruzione del classificatore");
        }
        try {
            Evaluation evaluation = new Evaluation(training);
            evaluation.evaluateModel(classifier, testing);
            return evaluation;
        } catch (Exception e) {
            logger.info("errore nello sviluppo del classificatore");
            return null;
        }
    }
    private Result generateResult(Evaluation evaluation, AnalysisVariables.Classifiers classifierType,
                                  AnalysisVariables.FeatureSelection featureSelection, AnalysisVariables.Sampling sampling) {
        double auc = evaluation.areaUnderROC(1);
        if (Double.isNaN(auc)) auc = 0;
        return new Result(
                lastRelease,
                classifierType, featureSelection, sampling,
                evaluation.precision(1),
                evaluation.recall(1),
                auc,
                evaluation.kappa());
    }
    private void applySampling(AnalysisVariables.Sampling sampling) {
        int yesInstances = calculateYes();
        int majority = Math.max(yesInstances, training.size() - yesInstances);
        double percent = 100 * 2 * ((double) majority) / training.size();
        if (percent < 50) percent = 100 - percent;
        switch (sampling) {
            case NONE -> {
                // does not need to apply anything
            }
            case UNDER_SAMPLING -> {
                try {
                    SpreadSubsample underSample = new SpreadSubsample();
                    underSample.setInputFormat(training);
                    underSample.setDistributionSpread(1.0);
                    training = Filter.useFilter(training, underSample);
                } catch (Exception e) {
                    logger.info("errore under sampling");
                }
            }
            case OVER_SAMPLING -> {
                try {
                    Resample overSample = new Resample();
                    overSample.setInputFormat(training);
                    overSample.setNoReplacement(false);
                    overSample.setBiasToUniformClass(1.0);
                    overSample.setSampleSizePercent(percent);
                    training = Filter.useFilter(training, overSample);
                } catch (Exception e) {
                    logger.info("errore over sampling");
                }
            }
            case SMOTE -> {
                try {
                    SMOTE smote = new SMOTE();
                    smote.setInputFormat(training);
                    smote.setPercentage(percent);
                    training = Filter.useFilter(training, smote);
                } catch (Exception e) {
                    logger.info("errore smoteg");
                }
            }
        }
    }

    private int calculateYes() {
        int buggy = 0;
        for (Instance instance : training) {
            if (instance.stringValue(instance.numAttributes() - 1).equals("true"))
                buggy += 1;
        }
        return buggy;
    }
    
}
