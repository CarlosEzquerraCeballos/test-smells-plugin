package es.upm.grise.profundizacion.file;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugins.annotations.LifecyclePhase;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//Añade defaultPhase = LifecyclePhase.TEST
@Mojo(name = "detect", defaultPhase = LifecyclePhase.TEST)
public class TsDetectMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "mainSrc", defaultValue = "src/main/java")
    private String mainSrc;

    @Parameter(property = "testSrc", defaultValue = "src/test/java")
    private String testSrc;

    @Parameter(property = "inputCsvName", defaultValue = "inputData.csv")
    private String inputCsvName;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("--- Inicio del Plugin TestSmellDetector ---");

        try {
            File targetDir = new File(project.getBuild().getDirectory());
            if (!targetDir.exists()) targetDir.mkdirs();

            // 1. Extraer JAR
            File detectorJar = extractResourceToFile("TestSmellDetector.jar", targetDir);

            // 2. Generar CSV de entrada (CORREGIDO: SIN CABECERA)
            Path inputCsvPath = generateInputCsv(targetDir);

            // 3. Ejecutar TestSmellDetector
            File rawResultFile = runTsDetect(detectorJar, inputCsvPath, targetDir);

            // 4. Procesar el resultado e imprimir resumen
            processOutputAndLogSummary(rawResultFile);

        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Error ejecutando TestSmellDetector", e);
        }
    }

    // =========================================================================
    // Métodos de UTILIDAD y EJECUCIÓN
    // =========================================================================

    private File extractResourceToFile(String resourceName, File targetDir) throws IOException {
        File targetFile = new File(targetDir, resourceName);
        if (targetFile.exists()) return targetFile;

        URL resourceUrl = getClass().getResource("/" + resourceName);
        if (resourceUrl == null) {
            throw new FileNotFoundException("Recurso no encontrado: " + resourceName);
        }

        try (InputStream in = resourceUrl.openStream();
             OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return targetFile;
    }

    private File runTsDetect(File jarFile, Path inputCsvPath, File targetDir) throws IOException, InterruptedException {
        getLog().info("Ejecutando TestSmellDetector...");
        
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-jar",
                jarFile.getAbsolutePath(),
                inputCsvPath.getFileName().toString()
        );

        pb.directory(targetDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Leemos logs solo si es necesario debuggear
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Solo mostramos errores graves o info relevante del proceso
                getLog().debug("[tsDetect] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            getLog().warn("TestSmellDetector finalizó con código: " + exitCode);
        }

        return findLatestResultFile(targetDir);
    }

    private File findLatestResultFile(File targetDir) throws FileNotFoundException {
        File[] matchingFiles = targetDir.listFiles((dir, name) -> 
            name.startsWith("Output_TestSmellDetection_") && name.endsWith(".csv")
        );

        if (matchingFiles == null || matchingFiles.length == 0) {
            throw new FileNotFoundException("No se encontró output en " + targetDir.getAbsolutePath());
        }

        return Arrays.stream(matchingFiles)
                .max(Comparator.comparingLong(File::lastModified))
                .orElseThrow(() -> new FileNotFoundException("Error buscando output reciente."));
    }

    private void processOutputAndLogSummary(File rawFile) throws IOException {
        if (!rawFile.exists() || rawFile.length() == 0) {
            getLog().warn("El archivo de resultados está vacío o no existe.");
            return;
        }

        getLog().info("---------------------------------------------------------------------------");
        getLog().info("                   RESUMEN DE TEST SMELLS DETECTADOS                       ");
        getLog().info("---------------------------------------------------------------------------");

        try (BufferedReader br = new BufferedReader(new FileReader(rawFile))) {
            String headerLine = br.readLine();
            if (headerLine == null) return;

            String[] tokens = headerLine.split(",");
            List<Integer> smellIndexes = new ArrayList<>();
            List<String> smellNames = new ArrayList<>();

            // Identificar columnas de olores (Ignoramos columnas de info)
            for (int i = 0; i < tokens.length; i++) {
                String col = tokens[i].trim();
                // Lista negra de columnas que NO son olores
                if (!col.equalsIgnoreCase("App") && 
                    !col.contains("Path") &&
                    !col.equalsIgnoreCase("TestClass") && 
                    !col.equalsIgnoreCase("ProductionClass") &&
                    !col.equalsIgnoreCase("RelativeTestFilePath") && 
                    !col.equalsIgnoreCase("RelativeProductionFilePath")) {

                    smellIndexes.add(i);
                    // Acortamos nombres muy largos para que quepa en la tabla
                    if(col.length() > 20) col = col.substring(0, 20);
                    smellNames.add(col);
                }
            }

            // IMPRIMIR CABECERA
            StringBuilder summaryHeader = new StringBuilder(String.format("%-25s", "ARCHIVO"));
            for (String name : smellNames) {
                summaryHeader.append(String.format(" | %-10s", name));
            }
            getLog().info(summaryHeader.toString());
            getLog().info("---------------------------------------------------------------------------");

            // IMPRIMIR FILAS
            String line;
            boolean dataFound = false;
            while ((line = br.readLine()) != null) {
                dataFound = true;
                String[] data = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                if (data.length < 2) continue;

                // TestClass suele ser columna 1 o 2. Ajuste defensivo.
                String testClassPath = (data.length > 1) ? data[1] : data[0];
                String simpleName = Paths.get(testClassPath).getFileName().toString().replace(".java", "");
                
                // Truncar nombre si es muy largo
                if(simpleName.length() > 24) simpleName = simpleName.substring(0, 21) + "...";

                StringBuilder summaryRow = new StringBuilder(String.format("%-25s", simpleName));

                for (int index : smellIndexes) {
                    String value = (index < data.length) ? data[index].trim() : "0";
                    // Si el valor es '0', lo pintamos suave, si es mayor, se nota más
                    summaryRow.append(String.format(" | %-10s", value));
                }
                getLog().info(summaryRow.toString());
            }
            
            if (!dataFound) {
                getLog().warn("El detector ha generado un archivo, pero no contiene filas de datos.");
                getLog().warn("Posible causa: Rutas incorrectas en inputData.csv");
            }
            
            getLog().info("---------------------------------------------------------------------------");
        }
    }

    // =========================================================================
    // Métodos de GENERACIÓN CSV
    // =========================================================================

    private Path generateInputCsv(File targetDir) throws IOException {
        Path projectRoot = Paths.get("").toAbsolutePath();
        String appName = projectRoot.getFileName().toString();

        List<Path> mainClasses;
        try (Stream<Path> walk = Files.walk(Paths.get(mainSrc))) {
            mainClasses = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }

        List<Path> testClasses;
        try (Stream<Path> walk = Files.walk(Paths.get(testSrc))) {
            testClasses = walk.filter(TsDetectMojo::isTestClass).collect(Collectors.toList());
        }

        Map<String, Path> testClassMap = new HashMap<>();
        for (Path test : testClasses) {
            String testName = simpleName(test);
            Set<String> prodNames = deriveProductionNames(testName);
            for (String prod : prodNames) {
                testClassMap.putIfAbsent(prod, test);
            }
        }

        List<String> csvLines = new ArrayList<>();
        // ---------------------------------------------------------------------
        // IMPORTANTE: NO AGREGAR CABECERA AQUÍ. EL DETECTOR FALLA SI LA HAY.
        // csvLines.add("appName,testClass,productionClass"); 
        // ---------------------------------------------------------------------

        for (Path prod : mainClasses) {
            String prodSimpleName = simpleName(prod);
            if (testClassMap.containsKey(prodSimpleName)) {
                Path testPath = testClassMap.get(prodSimpleName);
                
                // Formato esperado: AppName, PathToTest, PathToProd
                // Usamos replace \ por / para máxima compatibilidad con Java jars
                String line = appName + "," +
                        testPath.toAbsolutePath().toString().replace("\\", "/") + "," +
                        prod.toAbsolutePath().toString().replace("\\", "/");
                
                csvLines.add(line);
                getLog().debug("Entry added: " + line); // Log para debug
            }
        }
        
        if (csvLines.isEmpty()) {
            getLog().warn("¡CUIDADO! No se han encontrado parejas Test-Clase. El CSV de entrada está vacío.");
        }

        File outputFile = new File(targetDir, inputCsvName);
        Files.write(outputFile.toPath(), csvLines);

        getLog().info("CSV de entrada generado en: " + outputFile.getAbsolutePath());
        return outputFile.toPath();
    }

    private static boolean isTestClass(Path p) {
        String f = p.getFileName().toString();
        return f.startsWith("Test") && f.endsWith(".java") ||
                f.endsWith("Test.java") ||
                f.endsWith("Tests.java") ||
                f.endsWith("TestCase.java");
    }

    private static String simpleName(Path path) {
        String filename = path.getFileName().toString();
        return filename.substring(0, filename.lastIndexOf('.'));
    }

    private static Set<String> deriveProductionNames(String testSimpleName) {
        Set<String> names = new HashSet<>();
        if (testSimpleName.startsWith("Test"))
            names.add(testSimpleName.substring("Test".length()));
        if (testSimpleName.endsWith("Test"))
            names.add(testSimpleName.substring(0, testSimpleName.length() - "Test".length()));
        if (testSimpleName.endsWith("Tests"))
            names.add(testSimpleName.substring(0, testSimpleName.length() - "Tests".length()));
        if (testSimpleName.endsWith("TestCase"))
            names.add(testSimpleName.substring(0, testSimpleName.length() - "TestCase".length()));
        return names;
    }
}