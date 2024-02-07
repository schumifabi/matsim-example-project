package org.matsim.project;

import com.google.inject.internal.asm.$Type;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.contrib.emissions.EmissionModule;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.contrib.emissions.analysis.FastEmissionGridAnalyzer;
import org.matsim.contrib.emissions.analysis.EmissionsOnLinkEventHandler;
import org.matsim.contrib.emissions.analysis.EmissionsByPollutant;
import org.matsim.api.core.v01.network.Network;
import java.util.Map;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.emissions.analysis.Raster;
import java.util.Locale;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import org.matsim.contrib.analysis.time.TimeBinMap;
import org.matsim.contrib.noise.NoiseConfigGroup;
import org.matsim.contrib.noise.NoiseOfflineCalculation;
import org.matsim.contrib.noise.MergeNoiseCSVFile;
import org.matsim.contrib.noise.ProcessNoiseImmissions;

public class RunMatsim {

    public static void main(String[] args) {
        Config config;
        if (args == null || args.length == 0 || args[0] == null) {
            config = ConfigUtils.loadConfig("C:\\models\\scenario-sim-6\\config.xml", new EmissionsConfigGroup(), new NoiseConfigGroup());
        } else {
            config = ConfigUtils.loadConfig(args, new EmissionsConfigGroup(), new NoiseConfigGroup());
        }

        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);

        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(EmissionModule.class).asEagerSingleton();
            }
        });

        EmissionsOnLinkEventHandler emissionsHandler = new EmissionsOnLinkEventHandler(config.qsim().getTimeStepSize());
        controler.getEvents().addHandler(emissionsHandler);

        controler.run();

        performEmissionAnalysis(config, scenario);
        outputEmissionsData(emissionsHandler, config);

        performOfflineNoiseCalculation(config, scenario);
    }

    private static void performEmissionAnalysis(Config config, Scenario scenario) {
        String outputDirectory = config.controler().getOutputDirectory();
        String eventsFile = outputDirectory + "/output_events.xml.gz";
        String outputFile = outputDirectory + "/Emission_Grid_Analysis.csv";
        
        Network network = scenario.getNetwork();
        Map<Pollutant, Raster> analysisResults = FastEmissionGridAnalyzer.processEventsFile(eventsFile, network, 50, 30);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write("Pollutant,CellX,CellY,Value\n");
            analysisResults.forEach((pollutant, raster) -> {
                raster.forEachCoordinate((x, y, value) -> {
                    try {
                        writer.write(pollutant + "," + x + "," + y + "," + value + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void outputEmissionsData(EmissionsOnLinkEventHandler emissionsHandler, Config config) {
        String outputDirectory = config.controler().getOutputDirectory();
        String emissionsFile = outputDirectory + "/link_emissions.csv";

        try (FileWriter writer = new FileWriter(emissionsFile)) {
            writer.write("timeBin;linkId;pollutant;maxEmission\n");
            TimeBinMap<Map<Id<Link>, EmissionsByPollutant>> timeBins = emissionsHandler.getTimeBins();
            for (TimeBinMap.TimeBin<Map<Id<Link>, EmissionsByPollutant>> timeBin : timeBins.getTimeBins()) {
                double timeBinStart = timeBin.getStartTime();
                Map<Id<Link>, EmissionsByPollutant> emissionsByLink = timeBin.getValue();
                for (Map.Entry<Id<Link>, EmissionsByPollutant> entry : emissionsByLink.entrySet()) {
                    Id<Link> linkId = entry.getKey();
                    EmissionsByPollutant emissions = entry.getValue();
                    for (Map.Entry<Pollutant, Double> pollutantEntry : emissions.getEmissions().entrySet()) {
                        Pollutant pollutant = pollutantEntry.getKey();
                        double maxEmission = pollutantEntry.getValue();
                        String line = String.format(Locale.US, "%.2f;%s;%s;%.2f\n", timeBinStart, linkId, pollutant, maxEmission);
                        writer.write(line);
                    }
                }
            }
            System.out.println("Emissions data saved to " + emissionsFile);
        } catch (IOException e) {
            System.err.println("Error writing emissions data to file: " + e.getMessage());
        }
    }

    private static void performOfflineNoiseCalculation(Config config, Scenario scenario) {
        String outputDirectory = config.controler().getOutputDirectory();
        NoiseConfigGroup noiseParameters = ConfigUtils.addOrGetModule(config, NoiseConfigGroup.class);
        NoiseOfflineCalculation noiseCalculation = new NoiseOfflineCalculation(scenario, outputDirectory);
        noiseCalculation.run();

        String outputFilePath = outputDirectory + "noise-analysis/";
        ProcessNoiseImmissions process = new ProcessNoiseImmissions(outputFilePath + "immissions/", outputFilePath + "receiverPoints/receiverPoints.csv", noiseParameters.getReceiverPointGap());
        process.run();

        final String[] labels = { "immission", "consideredAgentUnits" , "damages_receiverPoint" };
        final String[] workingDirectories = { outputFilePath + "immissions/", outputFilePath + "consideredAgentUnits/", outputFilePath + "damages_receiverPoint/" };

        MergeNoiseCSVFile merger = new MergeNoiseCSVFile();
        merger.setReceiverPointsFile(outputFilePath + "receiverPoints/receiverPoints.csv");
        merger.setOutputDirectory(outputFilePath);
        merger.setTimeBinSize(noiseParameters.getTimeBinSizeNoiseComputation());
        merger.setWorkingDirectory(workingDirectories);
        merger.setLabel(labels);
        merger.run();
    }
}
