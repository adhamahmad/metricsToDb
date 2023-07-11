package com.example.metrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;

import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.DeploymentStrategyFluent.CustomParamsNested;

public class App {
    public static String username = "3d316df1-3861-47a3-854b-3649a3c801a0";
    public static String password = " kqA8Q~8Xo2-Iwislb91i73MlrDNRETCHLJxRdat7";
    public static String tenant = "dbb04fc7-056d-47a4-ae19-11d02acf6ec7";

    public static void main(String[] args) throws IOException, InterruptedException {
        Connection connection = connectToDb();

        KubernetesClient client = new DefaultKubernetesClient();
        String date = new Date().toString();
        DeploymentList deploymentList = client.apps().deployments().inNamespace("default").list();
        for (Deployment deployment : deploymentList.getItems()) { // loop on all deployments in namespace
            ResourceRequirements resources = deployment.getSpec().getTemplate().getSpec().getContainers().get(0)
                    .getResources();
            double cpuRequestValue = 0;
            if (resources.getRequests().get("cpu") != null) {
                String cpuRequest = resources.getRequests().get("cpu").toString();
                String numbersOnly = cpuRequest.replaceAll("[^0-9\\.]+", "");
                cpuRequestValue = Double.parseDouble(numbersOnly);
            }
            double memoryRequestValue = 0;
            if (resources.getRequests().get("memory") != null) {
                String memoryRequest = resources.getRequests().get("memory").toString();
                String numbersOnly = memoryRequest.replaceAll("[^0-9\\.]+", "");
                memoryRequestValue = Double.parseDouble(numbersOnly);
            }
            double cpuLimitValue = 0;
            if (resources.getLimits().get("cpu") != null) {
                String cpuLimit = resources.getLimits().get("cpu").toString();
                String numbersOnly = cpuLimit.replaceAll("[^0-9\\.]+", "");
                cpuLimitValue = Double.parseDouble(numbersOnly);

            }
            double memoryLimitValue = 0;
            if (resources.getLimits().get("memory") != null) {
                String memoryLimit = resources.getLimits().get("memory").toString();
                String numbersOnly = memoryLimit.replaceAll("[^0-9\\.]+", ""); // remove the metrics unit
                memoryLimitValue = Double.parseDouble(numbersOnly);

            }

            String deploymentName = deployment.getMetadata().getName();
            System.out.println("Deployment name: " + deploymentName);

            String tableName = deploymentName;
            createTable(connection, tableName);

            PodMetricsList podMetricsList = client.top().pods().inNamespace("default").metrics();
            for (PodMetrics podMetrics : podMetricsList.getItems()) { // loop on the deployment's pods
                String podName = podMetrics.getMetadata().getName();
                if (podMetrics.getMetadata().getLabels().get("app").equals(deployment.getMetadata().getName())
                        && isPodOldEnough(podName)) {
                    System.out.println("Pod name: " + podName);

                    double totalCpuUsage = 0.0;
                    double totalMemoryUsage = 0.0;

                    for (ContainerMetrics containerMetrics : podMetrics.getContainers()) { // loop on pod's containers
                        Quantity cpuUsage = containerMetrics.getUsage().get("cpu");
                        System.out.println("CPU format: " + cpuUsage.getFormat());
                        System.out.println("  Container name: " + containerMetrics.getName());
                        System.out.println("  CPU usage: " + cpuUsage.getAmount() + " " + cpuUsage.getFormat());
                        Quantity memoryUsage = containerMetrics.getUsage().get("memory");
                        System.out
                                .println("  Memory usage: " + memoryUsage.getAmount() + " " + memoryUsage.getFormat());
                        totalCpuUsage += Double.parseDouble(cpuUsage.getAmount()) / 1000000; // millicores = nanocores /
                                                                                             // 1,000,000
                        totalMemoryUsage += Double.parseDouble(memoryUsage.getAmount()) / 1024; // mebibyte = kibibyte /
                                                                                                // 1024
                    }
                    System.out.println("Total CPU usage: " + totalCpuUsage);
                    System.out.println("Total memory usage: " + totalMemoryUsage);
                    boolean success = false;
                    while (!success) { // retry in case of failure
                        try {
                            PreparedStatement useDatabaseStmt = connection.prepareStatement("USE metrics");
                            useDatabaseStmt.execute();
                            // Insert data into the pod_metrics table for the current deployment
                            String insertQuery = String.format(
                                    "INSERT INTO %s (timestamp, cpu_usage, memory_usage,pod_name,cpu_request,memory_request,cpu_limit,memory_limit) VALUES (?, ?, ?,?,?, ?, ?,?)",
                                    "`" + tableName + "`");
                            PreparedStatement podMetricsStmt;
                            podMetricsStmt = connection.prepareStatement(insertQuery);
                            podMetricsStmt.setDouble(8, memoryLimitValue);
                            podMetricsStmt.setDouble(7, cpuLimitValue);
                            podMetricsStmt.setDouble(6, memoryRequestValue);
                            podMetricsStmt.setDouble(5, cpuRequestValue);
                            podMetricsStmt.setString(4, podName);
                            podMetricsStmt.setString(1, date); // use the current date as the timestamp
                            podMetricsStmt.setDouble(2, totalCpuUsage);
                            podMetricsStmt.setDouble(3, totalMemoryUsage);
                            podMetricsStmt.executeUpdate();
                            success = true;
                        } catch (SQLException e) {
                            // TODO Auto-generated catch block
                            Thread.sleep(5000);
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private static void createTable(Connection connection, String tableName) {
        tableName = "`" + tableName + "`";
        try {
            PreparedStatement useDatabaseStmt = connection.prepareStatement("USE metrics");
            useDatabaseStmt.execute();
            PreparedStatement createTableStmt = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                            + "id INT NOT NULL AUTO_INCREMENT,"
                            + "pod_name VARCHAR(255) NOT NULL,"
                            + "  timestamp VARCHAR(45) NOT NULL,"
                            + "  cpu_usage DOUBLE NOT NULL,"
                            + "  memory_usage DOUBLE NOT NULL,"
                            + "  cpu_request DOUBLE ,"
                            + "  memory_request DOUBLE ,"
                            + "  cpu_limit DOUBLE ,"
                            + "  memory_limit DOUBLE,"
                            + "  PRIMARY KEY (id)"
                            + ")");
            createTableStmt.executeUpdate();
        } catch (SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private static Connection connectToDb() {
        Connection conn = null;
        Statement stmt = null;
        // .<namespace>.svc.cluster.local:<service-port>
        String jdbcUrl = "jdbc:mysql://mysql-service.proactive-autoscaler.svc.cluster.local:3306/";
        String username = "root";
        String password = "admin";

        try {
            conn = DriverManager.getConnection(jdbcUrl, username, password);
            stmt = conn.createStatement();

            // Check if the database exists
            ResultSet rs = stmt
                    .executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'metrics'");
            if (!rs.next()) {
                // If the database does not exist, create it
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS metrics");
                System.out.println("Created database metrics");
            } else {
                System.out.println("Database 'metrics' already exists");
            }

        } catch (SQLException e) {
            System.out.println("Error connecting to database: " + e.getMessage());
        }
        return conn;
    }

    private static String runTerminalCommands(String[] args) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        // System.out.println("##############################");
        // //System.out.println(String.join(" ", processBuilder.command().toArray(new
        // String[0])));
        // System.out.println("###############################");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
        }
        process.waitFor();
        return response.toString();
    }

    public static boolean isPodOldEnough(String podName) throws IOException, InterruptedException {
        // azure login
        String[] azLoginCommand = { "az", "login", "--service-principal", "-u", username, "-p",
                password, "--tenant", tenant };
        String resp1 = runTerminalCommands(azLoginCommand);
        System.out.println(resp1);
        // switch context
        String[] switchContextCommand = { "/bin/bash", "-c",
                "az aks get-credentials --name PAKCluster --resource-group PAKResourceGroup" };
        String resp2 = runTerminalCommands(switchContextCommand);
        System.out.println(resp2);
        String[] getPodsCommand = { "/bin/bash", "-c", "kubectl get pod " + podName + " -n default" };
        String unParseOutput = runTerminalCommands(getPodsCommand);
        String[] lines = unParseOutput.split("\n");
        if (lines.length < 2) {
            return false; // invalid input
        }
        String lastLine = lines[lines.length - 1];
        String[] parts = lastLine.split("\\s+");
        if (parts.length < 5) {
            return false; // invalid input
        }
        String age = parts[4];
        if (age.contains("h")) { // age is in hours
            return true;
        } else if (age.contains("d")) { // age is in days
            return true;
        } else if (age.endsWith("s")) { // age is in seconds
            if (age.contains("m")) {
                int minutes = Integer.parseInt(age.substring(0, age.indexOf("m")));
                return minutes >= 3;
            }
            return false;
        } else if (age.endsWith("m")) { // age is in minutes
            int minutes = Integer.parseInt(age.substring(0, age.length() - 1));
            return minutes >= 3;
        }
        return false; // age is not in any of the expected formats
    }
}