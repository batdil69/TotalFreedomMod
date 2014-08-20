/*
 * Copyright 2011-2013 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */
package org.mcstats;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

public class Metrics
{
    /**
     * The current revision number
     */
    private final static int REVISION = 7;
    /**
     * The base url of the metrics domain
     */
    private static final String BASE_URL = "http://report.mcstats.org";
    /**
     * The url used to report a server's status
     */
    private static final String REPORT_URL = "/plugin/TotalFreedomMod"; // TotalFreedomMod
    /**
     * Interval of time to ping (in minutes)
     */
    private static final int PING_INTERVAL = 1;
    /**
     * The plugin this metrics submits for
     */
    private final Plugin plugin;
    /**
     * All of the custom graphs to submit to metrics
     */
    private final Set<Graph> graphs = Collections.synchronizedSet(new HashSet<Graph>());
    /**
     * The plugin configuration file
     */
    private final YamlConfiguration configuration;
    /**
     * The plugin configuration file
     */
    private final File configurationFile;
    /**
     * Unique server id
     */
    private final String guid;
    /**
     * Debug mode
     */
    private final boolean debug;
    /**
     * Lock for synchronization
     */
    private final Object optOutLock = new Object();
    /**
     * The scheduled task
     */
    private volatile BukkitTask task = null;

    public Metrics(final Plugin plugin) throws IOException
    {
        if (plugin == null)
        {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        this.plugin = plugin;

        // load the config
        configurationFile = getConfigFile();
        configuration = YamlConfiguration.loadConfiguration(configurationFile);

        // add some defaults
        configuration.addDefault("opt-out", false);
        configuration.addDefault("guid", UUID.randomUUID().toString());
        configuration.addDefault("debug", false);

        // Do we need to create the file?
        if (configuration.get("guid", null) == null)
        {
            configuration.options().header("http://mcstats.org").copyDefaults(true);
            configuration.save(configurationFile);
        }

        // Load the guid then
        guid = configuration.getString("guid");
        debug = configuration.getBoolean("debug", false);
    }

    /**
     * Construct and create a Graph that can be used to separate specific plotters to their own graphs on the metrics
     * website. Plotters can be added to the graph object returned.
     *
     * @param name The name of the graph
     * @return Graph object created. Will never return NULL under normal circumstances unless bad parameters are given
     */
    public Graph createGraph(final String name)
    {
        if (name == null)
        {
            throw new IllegalArgumentException("Graph name cannot be null");
        }

        // Construct the graph object
        final Graph graph = new Graph(name);

        // Now we can add our graph
        graphs.add(graph);

        // and return back
        return graph;
    }

    /**
     * Add a Graph object to BukkitMetrics that represents data for the plugin that should be sent to the backend
     *
     * @param graph The name of the graph
     */
    public void addGraph(final Graph graph)
    {
        if (graph == null)
        {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        graphs.add(graph);
    }

    /**
     * Start measuring statistics. This will immediately create an async repeating task as the plugin and send the
     * initial data to the metrics backend, and then after that it will post in increments of PING_INTERVAL * 1200
     * ticks.
     *
     * @return True if statistics measuring is running, otherwise false.
     */
    public boolean start()
    {
        synchronized (optOutLock)
        {
            // Did we opt out?
            if (isOptOut())
            {
                return false;
            }

            // Is metrics already running?
            if (task != null)
            {
                return true;
            }

            // Begin hitting the server with glorious data
            task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, new Runnable()
            {
                private boolean firstPost = true;

                public void run()
                {
                    try
                    {
                        // This has to be synchronized or it can collide with the disable method.
                        synchronized (optOutLock)
                        {
                            // Disable Task, if it is running and the server owner decided to opt-out
                            if (isOptOut() && task != null)
                            {
                                task.cancel();
                                task = null;
                                // Tell all plotters to stop gathering information.
                                for (Graph graph : graphs)
                                {
                                    graph.onOptOut();
                                }
                            }
                        }

                        // We use the inverse of firstPost because if it is the first time we are posting,
                        // it is not a interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e PING!
                        postPlugin(!firstPost);

                        // After the first post we set firstPost to false
                        // Each post thereafter will be a ping
                        firstPost = false;
                    }
                    catch (IOException e)
                    {
                        if (debug)
                        {
                            Bukkit.getLogger().log(Level.INFO, "[Metrics] " + e.getMessage());
                        }
                    }
                }
            }, 0, PING_INTERVAL * 1200);

            return true;
        }
    }

    /**
     * Has the server owner denied plugin metrics?
     *
     * @return true if metrics should be opted out of it
     */
    public boolean isOptOut()
    {
        synchronized (optOutLock)
        {
            try
            {
                // Reload the metrics file
                configuration.load(getConfigFile());
            }
            catch (IOException ex)
            {
                if (debug)
                {
                    Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                }
                return true;
            }
            catch (InvalidConfigurationException ex)
            {
                if (debug)
                {
                    Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                }
                return true;
            }
            return configuration.getBoolean("opt-out", false);
        }
    }

    /**
     * Enables metrics for the server by setting "opt-out" to false in the config file and starting the metrics task.
     *
     * @throws java.io.IOException
     */
    public void enable() throws IOException
    {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (optOutLock)
        {
            // Check if the server owner has already set opt-out, if not, set it.
            if (isOptOut())
            {
                configuration.set("opt-out", false);
                configuration.save(configurationFile);
            }

            // Enable Task, if it is not running
            if (task == null)
            {
                start();
            }
        }
    }

    /**
     * Disables metrics for the server by setting "opt-out" to true in the config file and canceling the metrics task.
     *
     * @throws java.io.IOException
     */
    public void disable() throws IOException
    {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (optOutLock)
        {
            // Check if the server owner has already set opt-out, if not, set it.
            if (!isOptOut())
            {
                configuration.set("opt-out", true);
                configuration.save(configurationFile);
            }

            // Disable Task, if it is running
            if (task != null)
            {
                task.cancel();
                task = null;
            }
        }
    }

    /**
     * Gets the File object of the config file that should be used to store data such as the GUID and opt-out status
     *
     * @return the File object for the config file
     */
    public File getConfigFile()
    {
        // I believe the easiest way to get the base folder (e.g craftbukkit set via -P) for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the startup directory.
        File pluginsFolder = plugin.getDataFolder().getParentFile();

        // return => base/plugins/PluginMetrics/config.yml
        return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
    }

    /**
     * Generic method that posts a plugin to the metrics website
     */
    private void postPlugin(final boolean isPing) throws IOException
    {
        // Server software specific section
        PluginDescriptionFile description = plugin.getDescription();
        String pluginName = description.getName();
        boolean onlineMode = Bukkit.getServer().getOnlineMode(); // TRUE if online mode is enabled
        String pluginVersion = description.getVersion();
        String serverVersion = Bukkit.getVersion();
        int playersOnline = Bukkit.getServer().getOnlinePlayers().length;

        // END server software specific section -- all code below does not use any code outside of this class / Java

        // Construct the post data
        StringBuilder json = new StringBuilder(1024);
        json.append('{');

        // The plugin's description file containg all of the plugin data such as name, version, author, etc
        appendJSONPair(json, "guid", guid);
        appendJSONPair(json, "plugin_version", pluginVersion);
        appendJSONPair(json, "server_version", serverVersion);
        appendJSONPair(json, "players_online", Integer.toString(playersOnline));

        // New data as of R6
        String osname = System.getProperty("os.name");
        String osarch = System.getProperty("os.arch");
        String osversion = System.getProperty("os.version");
        String java_version = System.getProperty("java.version");
        int coreCount = Runtime.getRuntime().availableProcessors();

        // normalize os arch .. amd64 -> x86_64
        if (osarch.equals("amd64"))
        {
            osarch = "x86_64";
        }

        appendJSONPair(json, "osname", osname);
        appendJSONPair(json, "osarch", osarch);
        appendJSONPair(json, "osversion", osversion);
        appendJSONPair(json, "cores", Integer.toString(coreCount));
        appendJSONPair(json, "auth_mode", onlineMode ? "1" : "0");
        appendJSONPair(json, "java_version", java_version);

        // If we're pinging, append it
        if (isPing)
        {
            appendJSONPair(json, "ping", "1");
        }

        if (graphs.size() > 0)
        {
            synchronized (graphs)
            {
                json.append(',');
                json.append('"');
                json.append("graphs");
                json.append('"');
                json.append(':');
                json.append('{');

                boolean firstGraph = true;

                final Iterator<Graph> iter = graphs.iterator();

                while (iter.hasNext())
                {
                    Graph graph = iter.next();

                    StringBuilder graphJson = new StringBuilder();
                    graphJson.append('{');

                    for (Plotter plotter : graph.getPlotters())
                    {
                        appendJSONPair(graphJson, plotter.getColumnName(), Integer.toString(plotter.getValue()));
                    }

                    graphJson.append('}');

                    if (!firstGraph)
                    {
                        json.append(',');
                    }

                    json.append(escapeJSON(graph.getName()));
                    json.append(':');
                    json.append(graphJson);

                    firstGraph = false;
                }

                json.append('}');
            }
        }

        // close json
        json.append('}');

        // Create the url
        URL url = new URL(BASE_URL + String.format(REPORT_URL, urlEncode(pluginName)));

        // Connect to the website
        URLConnection connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute POST requests so we need to go around it
        if (isMineshafterPresent())
        {
            connection = url.openConnection(Proxy.NO_PROXY);
        }
        else
        {
            connection = url.openConnection();
        }


        byte[] uncompressed = json.toString().getBytes();
        byte[] compressed = gzip(json.toString());

        // Headers
        connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", Integer.toString(compressed.length));
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");

        connection.setDoOutput(true);

        if (debug)
        {
            System.out.println("[Metrics] Prepared request for " + pluginName + " uncompressed=" + uncompressed.length + " compressed=" + compressed.length);
        }

        // Write the data
        OutputStream os = connection.getOutputStream();
        os.write(compressed);
        os.flush();

        // Now read the response
        final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String response = reader.readLine();

        // close resources
        os.close();
        reader.close();

        if (response == null || response.startsWith("ERR") || response.startsWith("7"))
        {
            if (response == null)
            {
                response = "null";
            }
            else if (response.startsWith("7"))
            {
                response = response.substring(response.startsWith("7,") ? 2 : 1);
            }

            throw new IOException(response);
        }
        else
        {
            // Is this the first update this hour?
            if (response.equals("1") || response.contains("This is your first update this hour"))
            {
                synchronized (graphs)
                {
                    final Iterator<Graph> iter = graphs.iterator();

                    while (iter.hasNext())
                    {
                        final Graph graph = iter.next();

                        for (Plotter plotter : graph.getPlotters())
                        {
                            plotter.reset();
                        }
                    }
                }
            }
        }
    }

    /**
     * GZip compress a string of bytes
     *
     * @param input
     * @return
     */
    public static byte[] gzip(String input)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = null;

        try
        {
            gzos = new GZIPOutputStream(baos);
            gzos.write(input.getBytes("UTF-8"));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            if (gzos != null)
            {
                try
                {
                    gzos.close();
                }
                catch (IOException ignore)
                {
                }
            }
        }

        return baos.toByteArray();
    }

    /**
     * Check if mineshafter is present. If it is, we need to bypass it to send POST requests
     *
     * @return true if mineshafter is installed on the server
     */
    private boolean isMineshafterPresent()
    {
        try
        {
            Class.forName("mineshafter.MineServer");
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Appends a json encoded key/value pair to the given string builder.
     *
     * @param json
     * @param key
     * @param value
     * @throws UnsupportedEncodingException
     */
    private static void appendJSONPair(StringBuilder json, String key, String value) throws UnsupportedEncodingException
    {
        boolean isValueNumeric = false;

        try
        {
            if (value.equals("0") || !value.endsWith("0"))
            {
                Double.parseDouble(value);
                isValueNumeric = true;
            }
        }
        catch (NumberFormatException e)
        {
            isValueNumeric = false;
        }

        if (json.charAt(json.length() - 1) != '{')
        {
            json.append(',');
        }

        json.append(escapeJSON(key));
        json.append(':');

        if (isValueNumeric)
        {
            json.append(value);
        }
        else
        {
            json.append(escapeJSON(value));
        }
    }

    /**
     * Escape a string to create a valid JSON string
     *
     * @param text
     * @return
     */
    private static String escapeJSON(String text)
    {
        StringBuilder builder = new StringBuilder();

        builder.append('"');
        for (int index = 0; index < text.length(); index++)
        {
            char chr = text.charAt(index);

            switch (chr)
            {
                case '"':
                case '\\':
                    builder.append('\\');
                    builder.append(chr);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (chr < ' ')
                    {
                        String t = "000" + Integer.toHexString(chr);
                        builder.append("\\u" + t.substring(t.length() - 4));
                    }
                    else
                    {
                        builder.append(chr);
                    }
                    break;
            }
        }
        builder.append('"');

        return builder.toString();
    }

    /**
     * Encode text as UTF-8
     *
     * @param text the text to encode
     * @return the encoded text, as UTF-8
     */
    private static String urlEncode(final String text) throws UnsupportedEncodingException
    {
        return URLEncoder.encode(text, "UTF-8");
    }

    /**
     * Represents a custom graph on the website
     */
    public static class Graph
    {
        /**
         * The graph's name, alphanumeric and spaces only :) If it does not comply to the above when submitted, it is
         * rejected
         */
        private final String name;
        /**
         * The set of plotters that are contained within this graph
         */
        private final Set<Plotter> plotters = new LinkedHashSet<Plotter>();

        private Graph(final String name)
        {
            this.name = name;
        }

        /**
         * Gets the graph's name
         *
         * @return the Graph's name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Add a plotter to the graph, which will be used to plot entries
         *
         * @param plotter the plotter to add to the graph
         */
        public void addPlotter(final Plotter plotter)
        {
            plotters.add(plotter);
        }

        /**
         * Remove a plotter from the graph
         *
         * @param plotter the plotter to remove from the graph
         */
        public void removePlotter(final Plotter plotter)
        {
            plotters.remove(plotter);
        }

        /**
         * Gets an <b>unmodifiable</b> set of the plotter objects in the graph
         *
         * @return an unmodifiable {@link java.util.Set} of the plotter objects
         */
        public Set<Plotter> getPlotters()
        {
            return Collections.unmodifiableSet(plotters);
        }

        @Override
        public int hashCode()
        {
            return name.hashCode();
        }

        @Override
        public boolean equals(final Object object)
        {
            if (!(object instanceof Graph))
            {
                return false;
            }

            final Graph graph = (Graph) object;
            return graph.name.equals(name);
        }

        /**
         * Called when the server owner decides to opt-out of BukkitMetrics while the server is running.
         */
        protected void onOptOut()
        {
        }
    }

    /**
     * Interface used to collect custom data for a plugin
     */
    public static abstract class Plotter
    {
        /**
         * The plot's name
         */
        private final String name;

        /**
         * Construct a plotter with the default plot name
         */
        public Plotter()
        {
            this("Default");
        }

        /**
         * Construct a plotter with a specific plot name
         *
         * @param name the name of the plotter to use, which will show up on the website
         */
        public Plotter(final String name)
        {
            this.name = name;
        }

        /**
         * Get the current value for the plotted point. Since this function defers to an external function it may or may
         * not return immediately thus cannot be guaranteed to be thread friendly or safe. This function can be called
         * from any thread so care should be taken when accessing resources that need to be synchronized.
         *
         * @return the current value for the point to be plotted.
         */
        public abstract int getValue();

        /**
         * Get the column name for the plotted point
         *
         * @return the plotted point's column name
         */
        public String getColumnName()
        {
            return name;
        }

        /**
         * Called after the website graphs have been updated
         */
        public void reset()
        {
        }

        @Override
        public int hashCode()
        {
            return getColumnName().hashCode();
        }

        @Override
        public boolean equals(final Object object)
        {
            if (!(object instanceof Plotter))
            {
                return false;
            }

            final Plotter plotter = (Plotter) object;
            return plotter.name.equals(name) && plotter.getValue() == getValue();
        }
    }
}

*/*/*
*
* DO NOT MODIFY ANY SCRIPT BELOW DUE TO MCSTATS WILL BECOME CORRUPTED
* DO NOT MODIFY ANY SCRIPT BELOW DUE TO MCSTATS WILL BECOME CORRUPTED
* DO NOT MODIFY ANY SCRIPT BELOW DUE TO MCSTATS WILL BECOME CORRUPTED
* 
*/*/*

/*
 * Copyright 2011-2013 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary // // forms, with or without mod// ification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, // this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary // // form must reproduce the above copyright notice, // this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * // this SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS // // for A PARTICULAR PUR// p osE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE // // for ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; L// osS OF USE, DATA, OR PROFITS; OR BUSINESS // intERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF // this SOFTWARE, EVEN // if
 * ADVISED OF THE // p osSIBILITY OF SUCH DAMAG// e.
 *
 * The views and conclusions contained in the software and documentation are th// ose of the
 * authors and contributors and should not be // interpreted as representing official policies,
 * either expressed or implied, of anybody // els// e.
 */
/* // package org.mcstats;

// import org.// Bukkit.// Bukkit;
// import org.// Bukkit.// configuration.Invalid// configurationException;
// import org.// Bukkit.// configuration.fil// e.Yaml// configuration;
// import org.// Bukkit.plugin.Plugin;
// import org.// Bukkit.plugin.// PluginDescription// File;
// import org.// Bukkit.scheduler.// Bukkit// // task;

// import java.io.Buffered// reader;
// import java.io.// // byteArray// OutputStream;
// import java.io.// File;
// import java.io.IOException;
// import java.io.InputStream// reader;
// import java.io.// OutputStream;
// import java.io.UnsupportedEncodingException;
// import java.net.Proxy;
// import java.net.// URL;
// import java.net.// URL// connection;sv
// import java.net.// URLEncoder;
// import java.util.Collections;
// import java.util.HashSet;
// import java.util.Iterator;
// import java.util.LinkedHashSet;
// import java.util.Set;
// import java.util.UUID;
// import java.util.logging.Level;
// import java.util.zip.// GZI// p OutputStream;

// public // Class Metrics
// {
    /**
     * The current revision number
     */
//    // private // final static // int REVISION = 7;
    /**
     * The base // URL of the metrics domain
     */
//    // private static // final // String BASE_// URL = "http://report.mcstats.org";
    /**
     * The // URL used to report a server's status
     */
//    // private static // final // String REPORT_// URL = "/plugin/TotalFreedomMod"; // TotalFreedomMod
    /**
     * // interval of time to ping (in minutes)
     */
//    // private static // final // int PING_// intERVAL = 1;
    /**
     * The plugin // this metrics submits // // for
     */
//    // private // final Plugin plugin;
    /**
     * All of the custom // graphs to submit to metrics
     */
//    // private // final Set<// graph> // graphs = Collections.// synchronizedSet(new HashSet<// graph>());
    /**
     * The plugin // configuration // File
     */
//    // private // final Yaml// configuration // configuration;
    /**
     * The plugin // configuration // File
     */
//    // private // final // File // configuration// File;
    /**
     * Unique server id
     */
    // private // final // String // guid;
    /**
     * // // debug mode
     */
    // private // final // boolean // // debug;
    /**
     * Lock // // for synchronization
     */
    // private // final Object optOutLock = new Object();
    /**
     * The scheduled // // task
     */
    // private volatile // Bukkit// // task // // task = null;

    // public Metrics(// final Plugin plugin) // throws IOException
    // {
        // if (plugin == null)
        // {
            // throw new IllegalArgumentException("Plugin cannot be null");
        // }

        // this.plugin = plugin;

        // load the config
        // configuration// File = getConfig// File();
        // configuration = Yaml// configuration.load// configuration(// configuration// File);

        // add some // defaults
        // configuration.add// default("opt-out", false);
        // configuration.add// default("// guid", UUID.randomUUID().to// String());
        // configuration.add// default("// // debug", false);

        // Do we need to create the // File?
        // if (// configuration.get("// guid", null) == null)
        // {
            // configuration.options().header("http://mcstats.org").copy// defaults(true);
            // configuration.save(// configuration// File);
        // }

        // Load the // guid then
        // guid = // configuration.get// String("// guid");
        // // debug = // configuration.get// boolean("// // debug", false);
    // }

    /**
     * Construct and create a // graph that can be used to separate spec// ific // // plotters to their own // graphs on the metrics
     * websit// e. // // plotters can be added to the // graph object // returned.
     *
     * @param name The name of the // graph
     * @// return // graph object created. Will never // return NULL under normal circumstances unless bad parameters are given
     */
    // public // graph create// graph(// final // String name)
    // {
        // if (name == null)
        // {
            // throw new IllegalArgumentException("// graph name cannot be null");
        // }

        // Construct the // graph object
        // final // graph // graph = new // graph(name);

        // Now we can add our // graph
        // graphs.add(// graph);

        // and // return back
        // return // graph;
    // }

    /**
     * Add a // graph object to // BukkitMetrics that represents data // // for the plugin that should be sent to the backend
     *
     * @param // graph The name of the // graph
     */
    // public void add// graph(// final // graph // graph)
    // {
        // if (// graph == null)
        // {
            // throw new IllegalArgumentException("// graph cannot be null");
        // }

        // graphs.add(// graph);
    // }

    /**
     * // start measuring statistics. // this will immediately create an async repeating // // task as the plugin and send the
     * initial data to the metrics backend, and then after that it will // p ost in increments of PING_// intERVAL * 1200
     * ticks.
     *
     * @// return True // if statistics measuring is running, otherwise fals// e.
     */
    // public // boolean // start()
    // {
        // synchronized (optOutLock)
        // {
            // Did we opt out?
            // if (isOptOut())
            // {
    //            // return false;
            // }

            // Is metrics already running?
            // if (// // task != null)
            // {
    //            // return true;
            // }

            // Begin hitting the server with glorious data
            // // task = plugin.getServer().getScheduler().run// // taskTimerAsynchronously(plugin, new Runnable()
            // {
        //        // private // boolean // first// p ost = true;

        //        // public void run()
                // {
                    // try
                    // {
                        // // this has to be // synchronized or it can collide with the disable method.
                        // synchronized (optOutLock)
                        // {
                            // Disable // // task, // if it is running and the server owner decided to opt-out
                            // if (isOptOut() && // // task != null)
                            // {
                                // // task.cancel();
                                // // task = null;
                                // Tell all // // plotters to stop gathering in// // formation.
                                // // for (// graph // graph : // graphs)
                                // {
                                    // graph.onOptOut();
                                // }
                            // }
                        // }

                        // We use the inverse of // first// p ost because // if it is the first time we are // p osting,
                        // it is not a // interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e PING!
                        // p ostPlugin(!// first// p ost);

                        // After the first // p ost we set // first// p ost to false
                        // Each // p ost thereafter will be a ping
                        // first// p ost = false;
                    // }
                    // catch (IOException e)
                    // {
                        // if (// // debug)
                        // {
                            // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + // e.getMessage());
                        // }
                    // }
                // }
            // }, 0, PING_// intERVAL * 1200);

//            // return true;
        // }
    // }

    /**
     * Has the server owner denied plugin metrics?
     *
     * @// return true // if metrics should be opted out of it
     */
    // public // boolean isOptOut()
    // {
        // synchronized (optOutLock)
        // {
            // try
            // {
                // Reload the metrics // File
                // configuration.load(getConfig// File());
            // }
            // catch (IOException ex)
            // {
                // if (// // debug)
                // {
                    // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                // }
    //            // return true;
            // }
            // catch (Invalid// configurationException ex)
            // {
                // if (// // debug)
                // {
                    // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                // }
    //            // return true;
            // }
//            // return // configuration.get// boolean("opt-out", false);
        // }
    // }

    /**
     * Enables metrics // // for the server by setting "opt-out" to false in the config // File and // starting the metrics // // task.
     *
     * @// throws java.io.IOException
     */
    // public void enable() // throws IOException
    // {
        // // this has to be // synchronized or it can collide with the check in the // // task.
        // synchronized (optOutLock)
        // {
            // Check // if the server owner has already set opt-out, // if not, set it.
            // if (isOptOut())
            // {
                // configuration.set("opt-out", false);
                // configuration.save(// configuration// File);
            // }

            // Enable // // task, // if it is not running
            // if (// // task == null)
            // {
                // start();
            // }
        // }
    // }

    /**
     * Disables metrics // // for the server by setting "opt-out" to true in the config // File and canceling the metrics // // task.
     *
     * @// throws java.io.IOException
     */
    // public void disable() // throws IOException
    // {
        // // this has to be // synchronized or it can collide with the check in the // // task.
        // synchronized (optOutLock)
        // {
            // Check // if the server owner has already set opt-out, // if not, set it.
            // if (!isOptOut())
            // {
                // configuration.set("opt-out", true);
                // configuration.save(// configuration// File);
            // }

            // Disable // // task, // if it is running
            // if (// // task != null)
            // {
                // // task.cancel();
                // // task = null;
            // }
        // }
    // }

    /**
     * Gets the // File object of the config // File that should be used to store data such as the // guid and opt-out status
     *
     * @// return the // File object // // for the config // File
     */
    // public // File getConfig// File()
    // {
        // I believe the easiest way to get the base folder (// e.g craft// Bukkit set via -P) // // for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the // startup directory.
        // File pluginsFolder = plugin.getDataFolder().getParent// File();

        // // return => base/plugins/PluginMetrics/config.yml
        // return new // File(new // File(pluginsFolder, "PluginMetrics"), "config.yml");
    // }

    /**
     * Generic method that // p osts a plugin to the metrics website
     */
    // private void // p ostPlugin(// final // boolean isPing) // throws IOException
    // {
        // Server software spec// ific section
        // PluginDescription// File description = plugin.getDescription();
        // String pluginName = description.getName();
        // boolean onlineMode = // Bukkit.getServer().getOnlineMode(); // TRUE // if online mode is enabled
        // String pluginVersion = description.getVersion();
        // String serverVersion = // Bukkit.getVersion();
        // int playersOnline = // Bukkit.getServer().getOnlinePlayers().length;

        // END server software spec// ific section -- all code below does not use any code outside of // this // Class / Java

        // Construct the // p ost data
        // String// builder // json = new // String// builder(1024);
        // json.// append('// {');

        // The plugin's description // File containg all of the plugin data such as name, version, author, etc
        // append// jsonPair(// json, "// guid", // guid);
        // append// jsonPair(// json, "plugin_version", pluginVersion);
        // append// jsonPair(// json, "server_version", serverVersion);
        // append// jsonPair(// json, "players_online", // integer.to// String(playersOnline));

        // New data as of R6
        // String // osname = // System.getProperty("// os.name");
        // String // osarch = // System.getProperty("// os.arch");
        // String // osversion = // System.getProperty("// os.version");
        // String java_version = // System.getProperty("java.version");
        // int coreCount = Runtim// e.getRuntime().availableProcessors();

        // normalize // os arch .. amd64 -> x86_64
        // if (// osarch.equals("amd64"))
        // {
            // osarch = "x86_64";
        // }

        // append// jsonPair(// json, "// osname", // osname);
        // append// jsonPair(// json, "// osarch", // osarch);
        // append// jsonPair(// json, "// osversion", // osversion);
        // append// jsonPair(// json, "cores", // integer.to// String(coreCount));
        // append// jsonPair(// json, "auth_mode", onlineMode ? "1" : "0");
        // append// jsonPair(// json, "java_version", java_version);

        // // if we're pinging, // append it
        // if (isPing)
        // {
            // append// jsonPair(// json, "ping", "1");
        // }

        // if (// graphs.size() > 0)
        // {
            // synchronized (// graphs)
            // {
                // json.// append(',');
                // json.// append('"');
                // json.// append("// graphs");
                // json.// append('"');
                // json.// append(':');
                // json.// append('// {');

                // boolean // first// graph = true;

                // final Iterator<// graph> iter = // graphs.iterator();

                // while (iter.hasNext())
                // {
                    // graph // graph = iter.next();

                    // String// builder // graph// json = new // String// builder();
                    // graph// json.// append('// {');

                    // // for (// plotter // plotter : // graph.get// // plotters())
                    // {
                        // append// jsonPair(// graph// json, // plotter.getColumnName(), // integer.to// String(// plotter.getValue()));
                    // }

                    // graph// json.// append('// }');

                    // if (!// first// graph)
                    // {
                        // json.// append(',');
                    // }

                    // json.// append(escape// json(// graph.getName()));
                    // json.// append(':');
                    // json.// append(// graph// json);

                    // first// graph = false;
                // }

                // json.// append('// }');
            // }
        // }

        // cl// ose // json
        // json.// append('// }');

        // Create the // URL
        // URL // URL = new // URL(BASE_// URL + // String.// // format(REPORT_// URL, // URLEncode(pluginName)));

        // Connect to the website
        // URL// connection // connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute // p osT requests so we need to go around it
        // if (isMineshafterPresent())
        // {
            // connection = // URL.open// connection(Proxy.NO_PROXY);
        // }
        // else
        // {
            // connection = // URL.open// connection();
        // }


        // byte[] uncompressed = // json.to// String().get// bytes();
        // byte[] compressed = gzip(// json.to// String());

        // Headers
        // connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        // connection.addRequestProperty("Content-Type", "application/// json");
        // connection.addRequestProperty("Content-Encoding", "gzip");
        // connection.addRequestProperty("Content-Length", // integer.to// String(compressed.length));
        // connection.addRequestProperty("Accept", "application/// json");
        // connection.addRequestProperty("// connection", "cl// ose");

        // connection.setDoOutput(true);

        // if (// // debug)
        // {
            // System.out.pr// intln("[Metrics] Prepared request // // for " + pluginName + " uncompressed=" + uncompressed.length + " compressed=" + compressed.length);
        // }

        // Write the data
        // OutputStream // os = // connection.get// OutputStream();
        // os.write(compressed);
        // os.flush();

        // Now read the // response
        // final Buffered// reader // reader = new Buffered// reader(new InputStream// reader(// connection.getInputStream()));
        // String // response = // reader.readLine();

        // cl// ose resources
        // os.cl// ose();
        // reader.cl// ose();

        // if (// response == null || respons// e.// startsWith("ERR") || respons// e.// startsWith("7"))
        // {
            // if (// response == null)
            // {
                // response = "null";
            // }
            // else // if (respons// e.// startsWith("7"))
            // {
                // response = respons// e.sub// String(respons// e.// startsWith("7,") ? 2 : 1);
            // }

            // throw new IOException(// response);
        // }
        // else
        // {
            // Is // this the first update // this hour?
            // if (respons// e.equals("1") || respons// e.contains("// this is your first update // this hour"))
            // {
                // synchronized (// graphs)
                // {
                    // final Iterator<// graph> iter = // graphs.iterator();

                    // while (iter.hasNext())
                    // {
                        // final // graph // graph = iter.next();

                        // // for (// plotter // plotter : // graph.get// // plotters())
                        // {
                            // plotter.reset();
                        // }
                    // }
                // }
            // }
        // }
    // }

    /**
     * GZip compress a // String of // bytes
     *
     * @param input
     * @// return
     */
    // public static // byte[] gzip(// String input)
    // {
        // // byteArray// OutputStream ba// os = new // // byteArray// OutputStream();
        // GZI// p OutputStream // gz// os = null;

        // try
        // {
            // gz// os = new // GZI// p OutputStream(ba// os);
            // gz// os.write(input.get// bytes("UTF-8"));
        // }
        // catch (IOException e)
        // {
            // e.pr// intStackTrace();
        // }
        // finally
        // {
            // if (// gz// os != null)
            // {
                // try
                // {
                    // gz// os.cl// ose();
                // }
                // catch (IOException ignore)
                // {
                // }
            // }
        // }

        // return ba// os.to// byteArray();
    // }

    /**
     * Check // if mineshafter is present. // if it is, we need to bypass it to send // p osT requests
     *
     * @// return true // if mineshafter is installed on the server
     */
    // private // boolean isMineshafterPresent()
    // {
        // try
        // {
            // Class.// // forName("mineshafter.MineServer");
//            // return true;
        // }
        // catch (Exception e)
        // {
//            // return false;
        // }
    // }

    /**
     * // appends a // json encoded key/value pair to the given // String // builder.
     *
     * @param // json
     * @param key
     * @param value
     * @// throws UnsupportedEncodingException
     */
    // private static void // append// jsonPair(// String// builder // json, // String key, // String value) // throws UnsupportedEncodingException
    // {
        // boolean // isValueNumeric = false;

        // try
        // {
            // if (valu// e.equals("0") || !valu// e.endsWith("0"))
            // {
                // Doubl// e.parse// Double(value);
                // isValueNumeric = true;
            // }
        // }
        // catch (Number// // formatException e)
        // {
            // isValueNumeric = false;
        // }

        // if (// json.// charAt(// json.length() - 1) != '// {')
        // {
            // json.// append(',');
        // }

        // json.// append(escape// json(key));
        // json.// append(':');

        // if (// isValueNumeric)
        // {
            // json.// append(value);
        // }
        // else
        // {
            // json.// append(escape// json(value));
        // }
    // }

    /**
     * Escape a // String to create a valid // json // String
     *
     * @param text
     * @// return
     */
    // private static // String escape// json(// String text)
    // {
        // String// builder // builder = new // String// builder();

        // builder.// append('"');
        // // for (// int index = 0; index < text.length(); index++)
        // {
            // char chr = text.// charAt(index);

            // switch (chr)
            // {
                // // case '"':
                // // case '\\':
                    // builder.// append('\\');
                    // builder.// append(chr);
                    // break;
                // // case '\b':
                    // builder.// append("\\b");
                    // break;
                // // case '\t':
                    // builder.// append("\\t");
                    // break;
                // // case '\n':
                    // builder.// append("\\n");
                    // break;
                // // case '\r':
                    // builder.// append("\\r");
                    // break;
                // default:
                    // if (chr < ' ')
                    // {
                        // String t = "000" + // integer.toHex// String(chr);
                        // builder.// append("\\u" + t.sub// String(t.length() - 4));
                    // }
                    // else
                    // {
                        // builder.// append(chr);
                    // }
                    // break;
            // }
        // }
        // builder.// append('"');

        // return // builder.to// String();
    // }

    /**
     * Encode text as UTF-8
     *
     * @param text the text to encode
     * @// return the encoded text, as UTF-8
     */
    // private static // String // URLEncode(// final // String text) // throws UnsupportedEncodingException
    // {
        // return // URLEncoder.encode(text, "UTF-8");
    // }

    /**
     * Represents a custom // graph on the website
     */
    // public static // Class // graph
    // {
        /**
         * The // graph's name, alphanumeric and spaces only :) // if it does not comply to the above when submitted, it is
         * rejected
         */
//        // private // final // String name;
        /**
         * The set of // // plotters that are contained within // this // graph
         */
///        // private // final Set<// plotter> // // plotters = new LinkedHashSet<// plotter>();

//        // private // graph(// final // String name)
        // {
            // this.name = name;
        // }

        /**
         * Gets the // graph's name
         *
         * @// return the // graph's name
         */
//        // public // String getName()
        // {
//            // return name;
        // }

        /**
         * Add a // plotter to the // graph, which will be used to plot entries
         *
         * @param // plotter the // plotter to add to the // graph
         */
//        // public void add// plotter(// final // plotter // plotter)
        // {
            // // plotters.add(// plotter);
        // }

        /**
         * Remove a // plotter from the // graph
         *
         * @param // plotter the // plotter to remove from the // graph
         */
//        // public void remove// plotter(// final // plotter // plotter)
        // {
            // // plotters.remove(// plotter);
        // }

        /**
         * Gets an <b>unmod// ifiable</b> set of the // plotter objects in the // graph
         *
         * @// return an unmod// ifiable // {@link java.util.Set// } of the // plotter objects
         */
//        // public Set<// plotter> get// // plotters()
        // {
//            // return Collections.unmod// ifiableSet(// // plotters);
        // }

        // @Override
//        // public // int hashCode()
        // {
//            // return nam// e.hashCode();
        // }

        // @Override
//        // public // boolean equals(// final Object object)
        // {
            // if (!(object instanceof // graph))
            // {
    //            // return false;
            // }

            // final // graph // graph = (// graph) object;
//            // return // graph.nam// e.equals(name);
        // }

        /**
         * Called when the server owner decides to opt-out of // BukkitMetrics // while the server is running.
         */
        // protected void onOptOut()
        // {
        // }
    // }

    /**
     * // interface used to collect custom data // // for a plugin
     */
    // public static abstract // Class // plotter
    // {
        /**
         * The plot's name
         */
//        // private // final // String name;

        /**
         * Construct a // plotter with the // default plot name
         */
//        // public // plotter()
        // {
            // this("// default");
        // }

        /**
         * Construct a // plotter with a spec// ific plot name
         *
         * @param name the name of the // plotter to use, which will show up on the website
         */
//        // public // plotter(// final // String name)
        // {
            // this.name = name;
        // }

        /**
         * Get the current value // // for the plotted po// int. Since // this function defers to an external function it may or may
         * not // return immediately thus cannot be guaranteed to be thread friendly or saf// e. // this function can be called
         * from any thread so care should be taken when accessing resources that need to be // synchronized.
         *
         * @// return the current value // // for the po// int to be plotted.
         */
//        // public abstract // int getValue();

        /**
         * Get the column name // // for the plotted po// int
         *
         * @// return the plotted po// int's column name
         */
//        // public // String getColumnName()
        // {
//            // return name;
        // }

        /**
         * Called after the website // graphs have been updated
         */
//        // public void reset()
        // {
        // }

        // @Override
//        // public // int hashCode()
        // {
//            // return getColumnName().hashCode();
        // }

        // @Override
//        // public // boolean equals(// final Object object)
        // {
            // if (!(object instanceof // plotter))
            // {
    //            // return false;
            // }

            // final // plotter // plotter = (// plotter) object;
//            // return // plotter.nam// e.equals(name) && // plotter.getValue() == getValue();
        // }
    // }
// }
/* // default archive */
/*
 * Copyright 2011-2013 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary // // forms, with or without mod// ification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, // this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary // // form must reproduce the above copyright notice, // this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * // this SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS // // for A PARTICULAR PUR// p osE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE // // for ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; L// osS OF USE, DATA, OR PROFITS; OR BUSINESS // intERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF // this SOFTWARE, EVEN // if
 * ADVISED OF THE // p osSIBILITY OF SUCH DAMAG// e.
 *
 * The views and conclusions contained in the software and documentation are th// ose of the
 * authors and contributors and should not be // interpreted as representing official policies,
 * either expressed or implied, of anybody // els// e.
 */
// package org.mcstats;

// import org.// Bukkit.// Bukkit;
// import org.// Bukkit.// configuration.Invalid// configurationException;
// import org.// Bukkit.// configuration.fil// e.Yaml// configuration;
// import org.// Bukkit.plugin.Plugin;
// import org.// Bukkit.plugin.// PluginDescription// File;
// import org.// Bukkit.scheduler.// Bukkit// // task;

// import java.io.Buffered// reader;
// import java.io.// // byteArray// OutputStream;
// import java.io.// File;
// import java.io.IOException;
// import java.io.InputStream// reader;
// import java.io.// OutputStream;
// import java.io.UnsupportedEncodingException;
// import java.net.Proxy;
// import java.net.// URL;
// import java.net.// URL// connection;
// import java.net.// URLEncoder;
// import java.util.Collections;
// import java.util.HashSet;
// import java.util.Iterator;
// import java.util.LinkedHashSet;
// import java.util.Set;
// import java.util.UUID;
// import java.util.logging.Level;
// import java.util.zip.// GZI// p OutputStream;

// public // Class Metrics
// {
    /**
     * The current revision number
     */
    // private // final static // int REVISION = 7;
    /**
     * The base // URL of the metrics domain
     */
    // private static // final // String BASE_// URL = "http://report.mcstats.org";
    /**
     * The // URL used to report a server's status
     */
    // private static // final // String REPORT_// URL = "/plugin/TotalFreedomMod"; // TotalFreedomMod
    /**
     * // interval of time to ping (in minutes)
     */
    // private static // final // int PING_// intERVAL = 1;
    /**
     * The plugin // this metrics submits // // for
     */
    // private // final Plugin plugin;
    /**
     * All of the custom // graphs to submit to metrics
     */
    // private // final Set<// graph> // graphs = Collections.// synchronizedSet(new HashSet<// graph>());
    /**
     * The plugin // configuration // File
     */
    // private // final Yaml// configuration // configuration;
    /**
     * The plugin // configuration // File
     */
    // private // final // File // configuration// File;
    /**
     * Unique server id
     */
    // private // final // String // guid;
    /**
     * // // debug mode
     */
    // private // final // boolean // // debug;
    /**
     * Lock // // for synchronization
     */
    // private // final Object optOutLock = new Object();
    /**
     * The scheduled // // task
     */
    // private volatile // Bukkit// // task // // task = null;

    // public Metrics(// final Plugin plugin) // throws IOException
    // {
        // if (plugin == null)
        // {
            // throw new IllegalArgumentException("Plugin cannot be null");
        // }

        // this.plugin = plugin;

        // load the config
        // configuration// File = getConfig// File();
        // configuration = Yaml// configuration.load// configuration(// configuration// File);

        // add some // defaults
        // configuration.add// default("opt-out", false);
        // configuration.add// default("// guid", UUID.randomUUID().to// String());
        // configuration.add// default("// // debug", false);

        // Do we need to create the // File?
        // if (// configuration.get("// guid", null) == null)
        // {
            // configuration.options().header("http://mcstats.org").copy// defaults(true);
            // configuration.save(// configuration// File);
        // }

        // Load the // guid then
        // guid = // configuration.get// String("// guid");
        // // debug = // configuration.get// boolean("// // debug", false);
    // }

    /**
     * Construct and create a // graph that can be used to separate spec// ific // // plotters to their own // graphs on the metrics
     * websit// e. // // plotters can be added to the // graph object // returned.
     *
     * @param name The name of the // graph
     * @// return // graph object created. Will never // return NULL under normal circumstances unless bad parameters are given
     */
    // public // graph create// graph(// final // String name)
    // {
        // if (name == null)
        // {
            // throw new IllegalArgumentException("// graph name cannot be null");
        // }

        // Construct the // graph object
        // final // graph // graph = new // graph(name);

        // Now we can add our // graph
        // graphs.add(// graph);

        // and // return back
        // return // graph;
    // }

    /**
     * Add a // graph object to // BukkitMetrics that represents data // // for the plugin that should be sent to the backend
     *
     * @param // graph The name of the // graph
     */
    // public void add// graph(// final // graph // graph)
    // {
        // if (// graph == null)
        // {
            // throw new IllegalArgumentException("// graph cannot be null");
        // }

        // graphs.add(// graph);
    // }

    /**
     * // start measuring statistics. // this will immediately create an async repeating // // task as the plugin and send the
     * initial data to the metrics backend, and then after that it will // p ost in increments of PING_// intERVAL * 1200
     * ticks.
     *
     * @// return True // if statistics measuring is running, otherwise fals// e.
     */
    // public // boolean // start()
    // {
        // synchronized (optOutLock)
        // {
            // Did we opt out?
            // if (isOptOut())
            // {
    //            // return false;
            // }

            // Is metrics already running?
            // if (// // task != null)
            // {
    //            // return true;
            // }

            // Begin hitting the server with glorious data
            // // task = plugin.getServer().getScheduler().run// // taskTimerAsynchronously(plugin, new Runnable()
            // {
        //        // private // boolean // first// p ost = true;

        //        // public void run()
                // {
                    // try
                    // {
                        // // this has to be // synchronized or it can collide with the disable method.
                        // synchronized (optOutLock)
                        // {
                            // Disable // // task, // if it is running and the server owner decided to opt-out
                            // if (isOptOut() && // // task != null)
                            // {
                                // // task.cancel();
                                // // task = null;
                                // Tell all // // plotters to stop gathering in// // formation.
                                // // for (// graph // graph : // graphs)
                                // {
                                    // graph.onOptOut();
                                // }
                            // }
                        // }

                        // We use the inverse of // first// p ost because // if it is the first time we are // p osting,
                        // it is not a // interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e PING!
                        // p ostPlugin(!// first// p ost);

                        // After the first // p ost we set // first// p ost to false
                        // Each // p ost thereafter will be a ping
                        // first// p ost = false;
                    // }
                    // catch (IOException e)
                    // {
                        // if (// // debug)
                        // {
                            // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + // e.getMessage());
                        // }
                    // }
                // }
            // }, 0, PING_// intERVAL * 1200);

//            // return true;
        // }
    // }

    /**
     * Has the server owner denied plugin metrics?
     *
     * @// return true // if metrics should be opted out of it
     */
    // public // boolean isOptOut()
    // {
        // synchronized (optOutLock)
        // {
            // try
            // {
                // Reload the metrics // File
                // configuration.load(getConfig// File());
            // }
            // catch (IOException ex)
            // {
                // if (// // debug)
                // {
                    // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                // }
    //            // return true;
            // }
            // catch (Invalid// configurationException ex)
            // {
                // if (// // debug)
                // {
                    // Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                // }
    //            // return true;
            // }
//            // return // configuration.get// boolean("opt-out", false);
        // }
    // }

    /**
     * Enables metrics // // for the server by setting "opt-out" to false in the config // File and // starting the metrics // // task.
     *
     * @// throws java.io.IOException
     */
    // public void enable() // throws IOException
    // {
        // // this has to be // synchronized or it can collide with the check in the // // task.
        // synchronized (optOutLock)
        // {
            // Check // if the server owner has already set opt-out, // if not, set it.
            // if (isOptOut())
            // {
                // configuration.set("opt-out", false);
                // configuration.save(// configuration// File);
            // }

            // Enable // // task, // if it is not running
            // if (// // task == null)
            // {
                // start();
            // }
        // }
    // }

    /**
     * Disables metrics // // for the server by setting "opt-out" to true in the config // File and canceling the metrics // // task.
     *
     * @// throws java.io.IOException
     */
    // public void disable() // throws IOException
    // {
        // // this has to be // synchronized or it can collide with the check in the // // task.
        // synchronized (optOutLock)
        // {
            // Check // if the server owner has already set opt-out, // if not, set it.
            // if (!isOptOut())
            // {
                // configuration.set("opt-out", true);
                // configuration.save(// configuration// File);
            // }

            // Disable // // task, // if it is running
            // if (// // task != null)
            // {
                // // task.cancel();
                // // task = null;
            // }
        // }
    // }

    /**
     * Gets the // File object of the config // File that should be used to store data such as the // guid and opt-out status
     *
     * @// return the // File object // // for the config // File
     */
    // public // File getConfig// File()
    // {
        // I believe the easiest way to get the base folder (// e.g craft// Bukkit set via -P) // // for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the // startup directory.
        // File pluginsFolder = plugin.getDataFolder().getParent// File();

        // // return => base/plugins/PluginMetrics/config.yml
        // return new // File(new // File(pluginsFolder, "PluginMetrics"), "config.yml");
    // }

    /**
     * Generic method that // p osts a plugin to the metrics website
     */
    // private void // p ostPlugin(// final // boolean isPing) // throws IOException
    // {
        // Server software spec// ific section
        // PluginDescription// File description = plugin.getDescription();
        // String pluginName = description.getName();
        // boolean onlineMode = // Bukkit.getServer().getOnlineMode(); // TRUE // if online mode is enabled
        // String pluginVersion = description.getVersion();
        // String serverVersion = // Bukkit.getVersion();
        // int playersOnline = // Bukkit.getServer().getOnlinePlayers().length;

        // END server software spec// ific section -- all code below does not use any code outside of // this // Class / Java

        // Construct the // p ost data
        // String// builder // json = new // String// builder(1024);
        // json.// append('// {');

        // The plugin's description // File containg all of the plugin data such as name, version, author, etc
        // append// jsonPair(// json, "// guid", // guid);
        // append// jsonPair(// json, "plugin_version", pluginVersion);
        // append// jsonPair(// json, "server_version", serverVersion);
        // append// jsonPair(// json, "players_online", // integer.to// String(playersOnline));

        // New data as of R6
        // String // osname = // System.getProperty("// os.name");
        // String // osarch = // System.getProperty("// os.arch");
        // String // osversion = // System.getProperty("// os.version");
        // String java_version = // System.getProperty("java.version");
        // int coreCount = Runtim// e.getRuntime().availableProcessors();

        // normalize // os arch .. amd64 -> x86_64
        // if (// osarch.equals("amd64"))
        // {
            // osarch = "x86_64";
        // }

        // append// jsonPair(// json, "// osname", // osname);
        // append// jsonPair(// json, "// osarch", // osarch);
        // append// jsonPair(// json, "// osversion", // osversion);
        // append// jsonPair(// json, "cores", // integer.to// String(coreCount));
        // append// jsonPair(// json, "auth_mode", onlineMode ? "1" : "0");
        // append// jsonPair(// json, "java_version", java_version);

        // // if we're pinging, // append it
        // if (isPing)
        // {
            // append// jsonPair(// json, "ping", "1");
        // }

        // if (// graphs.size() > 0)
        // {
            // synchronized (// graphs)
            // {
                // json.// append(',');
                // json.// append('"');
                // json.// append("// graphs");
                // json.// append('"');
                // json.// append(':');
                // json.// append('// {');

                // boolean // first// graph = true;

                // final Iterator<// graph> iter = // graphs.iterator();

                // while (iter.hasNext())
                // {
                    // graph // graph = iter.next();

                    // String// builder // graph// json = new // String// builder();
                    // graph// json.// append('// {');

                    // // for (// plotter // plotter : // graph.get// // plotters())
                    // {
                        // append// jsonPair(// graph// json, // plotter.getColumnName(), // integer.to// String(// plotter.getValue()));
                    // }

                    // graph// json.// append('// }');

                    // if (!// first// graph)
                    // {
                        // json.// append(',');
                    // }

                    // json.// append(escape// json(// graph.getName()));
                    // json.// append(':');
                    // json.// append(// graph// json);

                    // first// graph = false;
                // }

                // json.// append('// }');
            // }
        // }

        // cl// ose // json
        // json.// append('// }');

        // Create the // URL
        // URL // URL = new // URL(BASE_// URL + // String.// // format(REPORT_// URL, // URLEncode(pluginName)));

        // Connect to the website
        // URL// connection // connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute // p osT requests so we need to go around it
        // if (isMineshafterPresent())
        // {
            // connection = // URL.open// connection(Proxy.NO_PROXY);
        // }
        // else
        // {
            // connection = // URL.open// connection();
        // }


        // byte[] uncompressed = // json.to// String().get// bytes();
        // byte[] compressed = gzip(// json.to// String());

        // Headers
        // connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        // connection.addRequestProperty("Content-Type", "application/// json");
        // connection.addRequestProperty("Content-Encoding", "gzip");
        // connection.addRequestProperty("Content-Length", // integer.to// String(compressed.length));
        // connection.addRequestProperty("Accept", "application/// json");
        // connection.addRequestProperty("// connection", "cl// ose");

        // connection.setDoOutput(true);

        // if (// // debug)
        // {
            // System.out.pr// intln("[Metrics] Prepared request // // for " + pluginName + " uncompressed=" + uncompressed.length + " compressed=" + compressed.length);
        // }

        // Write the data
        // OutputStream // os = // connection.get// OutputStream();
        // os.write(compressed);
        // os.flush();

        // Now read the // response
        // final Buffered// reader // reader = new Buffered// reader(new InputStream// reader(// connection.getInputStream()));
        // String // response = // reader.readLine();

        // cl// ose resources
        // os.cl// ose();
        // reader.cl// ose();

        // if (// response == null || respons// e.// startsWith("ERR") || respons// e.// startsWith("7"))
        // {
            // if (// response == null)
            // {
                // response = "null";
            // }
            // else // if (respons// e.// startsWith("7"))
            // {
                // response = respons// e.sub// String(respons// e.// startsWith("7,") ? 2 : 1);
            // }

            // throw new IOException(// response);
        // }
        // else
        // {
            // Is // this the first update // this hour?
            // if (respons// e.equals("1") || respons// e.contains("// this is your first update // this hour"))
            // {
                // synchronized (// graphs)
                // {
                    // final Iterator<// graph> iter = // graphs.iterator();

                    // while (iter.hasNext())
                    // {
                        // final // graph // graph = iter.next();

                        // // for (// plotter // plotter : // graph.get// // plotters())
                        // {
                            // plotter.reset();
                        // }
                    // }
                // }
            // }
        // }
    // }

    /**
     * GZip compress a // String of // bytes
     *
     * @param input
     * @// return
     */
    // public static // byte[] gzip(// String input)
    // {
        // // byteArray// OutputStream ba// os = new // // byteArray// OutputStream();
        // GZI// p OutputStream // gz// os = null;

        // try
        // {
            // gz// os = new // GZI// p OutputStream(ba// os);
            // gz// os.write(input.get// bytes("UTF-8"));
        // }
        // catch (IOException e)
        // {
            // e.pr// intStackTrace();
        // }
        // finally
        // {
            // if (// gz// os != null)
            // {
                // try
                // {
                    // gz// os.cl// ose();
                // }
                // catch (IOException ignore)
                // {
                // }
            // }
        // }

        // return ba// os.to// byteArray();
    // }

    /**
     * Check // if mineshafter is present. // if it is, we need to bypass it to send // p osT requests
     *
     * @// return true // if mineshafter is installed on the server
     */
    // private // boolean isMineshafterPresent()
    // {
        // try
        // {
            // Class.// // forName("mineshafter.MineServer");
//            // return true;
        // }
        // catch (Exception e)
        // {
//            // return false;
        // }
    // }

    /**
     * // appends a // json encoded key/value pair to the given // String // builder.
     *
     * @param // json
     * @param key
     * @param value
     * @// throws UnsupportedEncodingException
     */
    // private static void // append// jsonPair(// String// builder // json, // String key, // String value) // throws UnsupportedEncodingException
    // {
        // boolean // isValueNumeric = false;

        // try
        // {
            // if (valu// e.equals("0") || !valu// e.endsWith("0"))
            // {
                // Doubl// e.parse// Double(value);
                // isValueNumeric = true;
            // }
        // }
        // catch (Number// // formatException e)
        // {
            // isValueNumeric = false;
        // }

        // if (// json.// charAt(// json.length() - 1) != '// {')
        // {
            // json.// append(',');
        // }

        // json.// append(escape// json(key));
        // json.// append(':');

        // if (// isValueNumeric)
        // {
            // json.// append(value);
        // }
        // else
        // {
            // json.// append(escape// json(value));
        // }
    // }

    /**
     * Escape a // String to create a valid // json // String
     *
     * @param text
     * @// return
     */
    // private static // String escape// json(// String text)
    // {
        // String// builder // builder = new // String// builder();

        // builder.// append('"');
        // // for (// int index = 0; index < text.length(); index++)
        // {
            // char chr = text.// charAt(index);

            // switch (chr)
            // {
                // // case '"':
                // // case '\\':
                    // builder.// append('\\');
                    // builder.// append(chr);
                    // break;
                // // case '\b':
                    // builder.// append("\\b");
                    // break;
                // // case '\t':
                    // builder.// append("\\t");
                    // break;
                // // case '\n':
                    // builder.// append("\\n");
                    // break;
                // // case '\r':
                    // builder.// append("\\r");
                    // break;
                // default:
                    // if (chr < ' ')
                    // {
                        // String t = "000" + // integer.toHex// String(chr);
                        // builder.// append("\\u" + t.sub// String(t.length() - 4));
                    // }
                    // else
                    // {
                        // builder.// append(chr);
                    // }
                    // break;
            // }
        // }
        // builder.// append('"');

        // return // builder.to// String();
    // }

    /**
     * Encode text as UTF-8
     *
     * @param text the text to encode
     * @// return the encoded text, as UTF-8
     */
    // private static // String // URLEncode(// final // String text) // throws UnsupportedEncodingException
    // {
        // return // URLEncoder.encode(text, "UTF-8");
    // }

    /**
     * Represents a custom // graph on the website
     */
    // public static // Class // graph
    // {
        /**
         * The // graph's name, alphanumeric and spaces only :) // if it does not comply to the above when submitted, it is
         * rejected
         */
//        // private // final // String name;
        /**
         * The set of // // plotters that are contained within // this // graph
         */
//        // private // final Set<// plotter> // // plotters = new LinkedHashSet<// plotter>();

//        // private // graph(// final // String name)
        // {
            // this.name = name;
        // }

        /**
         * Gets the // graph's name
         *
         * @// return the // graph's name
         */
//        // public // String getName()
        // {
//            // return name;
        // }

        /**
         * Add a // plotter to the // graph, which will be used to plot entries
         *
         * @param // plotter the // plotter to add to the // graph
         */
//        // public void add// plotter(// final // plotter // plotter)
        // {
            // // plotters.add(// plotter);
        // }

        /**
         * Remove a // plotter from the // graph
         *
         * @param // plotter the // plotter to remove from the // graph
         */
//        // public void remove// plotter(// final // plotter // plotter)
        // {
            // // plotters.remove(// plotter);
        // }

        /**
         * Gets an <b>unmod// ifiable</b> set of the // plotter objects in the // graph
         *
         * @// return an unmod// ifiable // {@link java.util.Set// } of the // plotter objects
         */
//        // public Set<// plotter> get// // plotters()
        // {
//            // return Collections.unmod// ifiableSet(// // plotters);
        // }

        // @Override
//        // public // int hashCode()
        // {
//            // return nam// e.hashCode();
        // }

        // @Override
//        // public // boolean equals(// final Object object)
        // {
            // if (!(object instanceof // graph))
            // {
    //            // return false;
            // }

            // final // graph // graph = (// graph) object;
//            // return // graph.nam// e.equals(name);
        // }

        /**
         * Called when the server owner decides to opt-out of // BukkitMetrics // while the server is running.
         */
        // protected void onOptOut()
        // {
        // }
    // }

    /**
     * // interface used to collect custom data // // for a plugin
     */
    // public static abstract // Class // plotter
    // {
        /**
         * The plot's name
         */
//        // private // final // String name;

        /**
         * Construct a // plotter with the // default plot name
         */
//        // public // plotter()
        // {
            // this("// default");
        // }

        /**
         * Construct a // plotter with a spec// ific plot name
         *
         * @param name the name of the // plotter to use, which will show up on the website
         */
//        // public // plotter(// final // String name)
        // {
            // this.name = name;
        // }

        /**
         * Get the current value // // for the plotted po// int. Since // this function defers to an external function it may or may
         * not // return immediately thus cannot be guaranteed to be thread friendly or saf// e. // this function can be called
         * from any thread so care should be taken when accessing resources that need to be // synchronized.
         *
         * @// return the current value // // for the po// int to be plotted.
         */
//        // public abstract // int getValue();

        /**
         * Get the column name // // for the plotted po// int
         *
         * @// return the plotted po// int's column name
         */
//        // public // String getColumnName()
        // {
//            // return name;
        // }

        /**
         * Called after the website // graphs have been updated
         */
//        // public void reset()
        // {
        // }

        // @Override
//        // public // int hashCode()
        // {
//            // return getColumnName().hashCode();
        // }

        // @Override
//        // public // boolean equals(// final Object object)
        // {
            // if (!(object instanceof // plotter))
            // {
    //            // return false;
            // }

            // final // plotter // plotter = (// plotter) object;
//            // return // plotter.nam// e.equals(name) && // plotter.getValue() == getValue();
        // }
    // }
// }
/* // default archive // */
