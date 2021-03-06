/******************************************************************************
 * Multiverse 2 Copyright (c) the Multiverse Team 2011.                       *
 * Multiverse 2 is licensed under the BSD License.                            *
 * For more information please check the README.md file included              *
 * with this project.                                                         *
 ******************************************************************************/

package com.onarandombox.MultiverseCore;

import com.fernferret.allpay.AllPay;
import com.fernferret.allpay.GenericBank;
import com.onarandombox.MultiverseCore.api.Core;
import com.onarandombox.MultiverseCore.api.MVPlugin;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import com.onarandombox.MultiverseCore.commands.*;
import com.onarandombox.MultiverseCore.destination.*;
import com.onarandombox.MultiverseCore.listeners.MVEntityListener;
import com.onarandombox.MultiverseCore.listeners.MVPlayerListener;
import com.onarandombox.MultiverseCore.listeners.MVPluginListener;
import com.onarandombox.MultiverseCore.listeners.MVWeatherListener;
import com.onarandombox.MultiverseCore.utils.*;
import com.pneumaticraft.commandhandler.CommandHandler;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The implementation of the Multiverse-{@link Core}.
 */
public class MultiverseCore extends JavaPlugin implements MVPlugin, Core {
    private final static int Protocol = 10;
    // Global Multiverse config variable, states whether or not
    // Multiverse should stop other plugins from teleporting players
    // to worlds.
    public static boolean EnforceAccess;
    public static boolean EnforceGameModes;
    public static boolean PrefixChat;
    public static boolean DisplayPermErrors;
    public static boolean TeleportIntercept;
    public static Map<String, String> teleportQueue = new HashMap<String, String>();
    private AnchorManager anchorManager = new AnchorManager(this);

    /**
     * This method is used to find out who is teleporting a player.
     * @param playerName The teleported player (the teleportee).
     * @return The player that teleported the other one (the teleporter).
     */
    public static String getPlayerTeleporter(String playerName) {
        if (teleportQueue.containsKey(playerName)) {
            String teleportee = teleportQueue.get(playerName);
            teleportQueue.remove(playerName);
            return teleportee;
        }
        return null;
    }

    public static void addPlayerToTeleportQueue(String teleporter, String teleportee) {
        staticLog(Level.FINEST, "Adding mapping '" + teleporter + "' => '" + teleportee + "' to teleport queue");
        teleportQueue.put(teleportee, teleporter);
    }

    @Override
    public String toString() {
        return "The Multiverse-Core Plugin";
    }

    @Override
    public String dumpVersionInfo(String buffer) {
        // I'm kinda cheating on this one, since we call the init event.
        return buffer;
    }

    @Override
    public MultiverseCore getCore() {
        return this;
    }

    @Override
    public void setCore(MultiverseCore core) {
        // This method is required by the interface (so core is effectively a plugin of itself) and therefore
        // this is never used.
    }

    @Override
    public int getProtocolVersion() {
        return MultiverseCore.Protocol;
    }

    // Useless stuff to keep us going.
    private static final Logger log = Logger.getLogger("Minecraft");
    private static DebugLog debugLog;
    public static boolean MobsDisabledInDefaultWorld = false;

    // Setup our Map for our Commands using the CommandHandler.
    private CommandHandler commandHandler;

    private final static String tag = "[Multiverse-Core]";

    // Multiverse Permissions Handler
    private MVPermissions ph;

    // Configurations
    private FileConfiguration multiverseConfig = null;

    private MVWorldManager worldManager = new WorldManager(this);

    // Setup the block/player/entity listener.
    private MVPlayerListener playerListener = new MVPlayerListener(this);

    private MVEntityListener entityListener = new MVEntityListener(this);
    private MVPluginListener pluginListener = new MVPluginListener(this);
    private MVWeatherListener weatherListener = new MVWeatherListener(this);

    public UpdateChecker updateCheck;

    public static int GlobalDebug = 0;

    // HashMap to contain information relating to the Players.
    private HashMap<String, MVPlayerSession> playerSessions;
    private GenericBank bank = null;
    private AllPay banker;
    protected int pluginCount;
    private DestinationFactory destFactory;
    private SpoutInterface spoutInterface = null;
    private double allpayversion = 3;
    private double chversion = 4;
    private MVMessaging messaging;

    private File serverFolder = new File(System.getProperty("user.dir"));

    @Override
    public void onLoad() {
        // Create our DataFolder
        getDataFolder().mkdirs();
        // Setup our Debug Log
        debugLog = new DebugLog("Multiverse-Core", getDataFolder() + File.separator + "debug.log");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileConfiguration getMVConfiguration() {
        return this.multiverseConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GenericBank getBank() {
        return this.bank;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onEnable() {
        //this.worldManager = new WorldManager(this);
        // Perform initial checks for AllPay
        if (!this.validateAllpay() || !this.validateCH()) {
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.banker = new AllPay(this, tag + " ");
        // Output a little snippet to show it's enabled.
        this.log(Level.INFO, "- Version " + this.getDescription().getVersion() + " (API v" + Protocol + ") Enabled - By " + getAuthors());
        // Load the defaultWorldGenerators
        this.worldManager.getDefaultWorldGenerators();

        this.registerEvents();
        // Setup Permissions, we'll do an initial check for the Permissions plugin then fall back on isOP().
        this.ph = new MVPermissions(this);

        this.bank = this.banker.loadEconPlugin();

        // Setup the command manager
        this.commandHandler = new CommandHandler(this, this.ph);
        // Call the Function to assign all the Commands to their Class.
        this.registerCommands();

        // Initialize the Destination factor AFTER the commands
        this.initializeDestinationFactory();

        this.playerSessions = new HashMap<String, MVPlayerSession>();

        // Start the Update Checker
        // updateCheck = new UpdateChecker(this.getDescription().getName(), this.getDescription().getVersion());

        // Call the Function to load all the Worlds and setup the HashMap
        // When called with null, it tries to load ALL
        // this function will be called every time a plugin registers a new envtype with MV
        // Setup & Load our Configuration files.
        loadConfigs();
        if (this.multiverseConfig != null) {
            this.worldManager.loadDefaultWorlds();
            this.worldManager.loadWorlds(true);
        } else {
            this.log(Level.SEVERE, "Your configs were not loaded. Very little will function in Multiverse.");
        }
        this.anchorManager.loadAnchors();
    }

    private boolean validateAllpay() {
        try {
            this.banker = new AllPay(this, "Verify");
            if (this.banker.getVersion() >= allpayversion) {
                return true;
            } else {
                log.info(tag + " - Version " + this.getDescription().getVersion() + " was NOT ENABLED!!!");
                log.info(tag + " A plugin that has loaded before " + this.getDescription().getName() + " has an incompatible version of AllPay!");
                log.info(tag + " The Following Plugins MAY out of date!");
                log.info(tag + " This plugin needs AllPay v" + allpayversion + " or higher and another plugin has loaded v" + this.banker.getVersion() + "!");
                log.info(tag + AllPay.pluginsThatUseUs.toString());
                return false;
            }
        } catch (Throwable t) {
        }
        log.info(tag + " - Version " + this.getDescription().getVersion() + " was NOT ENABLED!!!");
        log.info(tag + " A plugin that has loaded before " + this.getDescription().getName() + " has an incompatible version of AllPay!");
        log.info(tag + " Check the logs for [AllPay] - Version ... for PLUGIN NAME to find the culprit! Then Yell at that dev!");
        log.info(tag + " Or update that plugin :P");
        log.info(tag + " This plugin needs AllPay v" + allpayversion + " or higher!");
        return false;
    }

    private boolean validateCH() {
        try {
            this.commandHandler = new CommandHandler(this, null);
            if (this.commandHandler.getVersion() >= chversion) {
                return true;
            } else {
                log.info(tag + " - Version " + this.getDescription().getVersion() + " was NOT ENABLED!!!");
                log.info(tag + " A plugin that has loaded before " + this.getDescription().getName() + " has an incompatible version of CommandHandler (an internal library)!");
                log.info(tag + " Please contact this plugin author!!!!!!!");
                log.info(tag + " This plugin needs CommandHandler v" + chversion + " or higher and another plugin has loaded v" + this.commandHandler.getVersion() + "!");
                return false;
            }
        } catch (Throwable t) {
        }
        log.info(tag + " - Version " + this.getDescription().getVersion() + " was NOT ENABLED!!!");
        log.info(tag + " A plugin that has loaded before " + this.getDescription().getName() + " has an incompatible version of CommandHandler (an internal library)!");
        log.info(tag + " Please contact this plugin author!!!!!!!");
        log.info(tag + " This plugin needs CommandHandler v" + chversion + " or higher!");
        return false;
    }

    private void initializeDestinationFactory() {
        this.destFactory = new DestinationFactory(this);
        this.destFactory.registerDestinationType(WorldDestination.class, "");
        this.destFactory.registerDestinationType(WorldDestination.class, "w");
        this.destFactory.registerDestinationType(ExactDestination.class, "e");
        this.destFactory.registerDestinationType(PlayerDestination.class, "pl");
        this.destFactory.registerDestinationType(CannonDestination.class, "ca");
        this.destFactory.registerDestinationType(BedDestination.class, "b");
        this.destFactory.registerDestinationType(AnchorDestination.class, "a");
    }

    /**
     * Function to Register all the Events needed.
     */
    private void registerEvents() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Event.Type.PLAYER_TELEPORT, this.playerListener, Priority.Highest, this); // Cancel Teleports if needed.
        pm.registerEvent(Event.Type.PLAYER_JOIN, this.playerListener, Priority.Normal, this); // To create the Player Session
        pm.registerEvent(Event.Type.PLAYER_QUIT, this.playerListener, Priority.Normal, this); // To remove Player Sessions
        pm.registerEvent(Event.Type.PLAYER_RESPAWN, this.playerListener, Priority.Low, this); // Let plugins which specialize in (re)spawning carry more weight.
        pm.registerEvent(Event.Type.PLAYER_LOGIN, this.playerListener, Priority.Low, this); // Let plugins which specialize in (re)spawning carry more weight.
        pm.registerEvent(Event.Type.PLAYER_CHAT, this.playerListener, Priority.Normal, this); // To prepend the world name
        pm.registerEvent(Event.Type.PLAYER_PORTAL, this.playerListener, Priority.Lowest, this); // To switch gamemode
        pm.registerEvent(Event.Type.PLAYER_CHANGED_WORLD, this.playerListener, Priority.Monitor, this); // To switch gamemode

        pm.registerEvent(Event.Type.ENTITY_REGAIN_HEALTH, this.entityListener, Priority.Normal, this);
        pm.registerEvent(Event.Type.ENTITY_DAMAGE, this.entityListener, Priority.Normal, this); // To Allow/Disallow fake PVP
        pm.registerEvent(Event.Type.CREATURE_SPAWN, this.entityListener, Priority.Normal, this); // To prevent all or certain animals/monsters from spawning.
        pm.registerEvent(Event.Type.FOOD_LEVEL_CHANGE, this.entityListener, Priority.Normal, this);

        pm.registerEvent(Event.Type.PLUGIN_ENABLE, this.pluginListener, Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLUGIN_DISABLE, this.pluginListener, Priority.Monitor, this);

        pm.registerEvent(Event.Type.WEATHER_CHANGE, this.weatherListener, Priority.Normal, this);
        pm.registerEvent(Event.Type.THUNDER_CHANGE, this.weatherListener, Priority.Normal, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void loadConfigs() {
        // Now grab the Configuration Files.
        this.multiverseConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        Configuration coreDefaults = YamlConfiguration.loadConfiguration(this.getClass().getResourceAsStream("/defaults/config.yml"));
        this.multiverseConfig.setDefaults(coreDefaults);
        this.multiverseConfig.options().copyDefaults(true);
        this.saveMVConfig();
        this.multiverseConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        this.worldManager.loadWorldConfig(new File(getDataFolder(), "worlds.yml"));

        // Setup the Debug option, we'll default to false because this option will not be in the default config.
        GlobalDebug = this.multiverseConfig.getInt("debug", 0);
        // Lets cache these values due to the fact that they will be accessed many times.
        EnforceAccess = this.multiverseConfig.getBoolean("enforceaccess", false);
        EnforceGameModes = this.multiverseConfig.getBoolean("enforcegamemodes", true);
        PrefixChat = this.multiverseConfig.getBoolean("worldnameprefix", true);
        DisplayPermErrors = this.multiverseConfig.getBoolean("displaypermerrors", true);
        TeleportIntercept = this.multiverseConfig.getBoolean("teleportintercept", true);
        // Default as the server.props world.
        this.worldManager.setFirstSpawnWorld(this.multiverseConfig.getString("firstspawnworld", getDefaultWorldName()));
        DisplayPermErrors = this.multiverseConfig.getBoolean("displaypermerrors", true);
        this.messaging = new MVMessaging(this);
        this.messaging.setCooldown(this.multiverseConfig.getInt("messagecooldown", 5000));
        this.saveMVConfigs();
    }

    /**
     * Safely return a world name
     * (The tests call this with no worlds loaded)
     *
     * @return The default world name.
     */
    private String getDefaultWorldName() {
        if (this.getServer().getWorlds().size() > 0) {
            return this.getServer().getWorlds().get(0).getName();
        }
        return "";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MVMessaging getMessaging() {
        return this.messaging;
    }

    /**
     * Register Multiverse-Core commands to Command Manager.
     */
    private void registerCommands() {
        // Intro Commands
        this.commandHandler.registerCommand(new HelpCommand(this));
        this.commandHandler.registerCommand(new VersionCommand(this));
        this.commandHandler.registerCommand(new ListCommand(this));
        this.commandHandler.registerCommand(new InfoCommand(this));
        this.commandHandler.registerCommand(new CreateCommand(this));
        this.commandHandler.registerCommand(new ImportCommand(this));
        this.commandHandler.registerCommand(new ReloadCommand(this));
        this.commandHandler.registerCommand(new SetSpawnCommand(this));
        this.commandHandler.registerCommand(new CoordCommand(this));
        this.commandHandler.registerCommand(new TeleportCommand(this));
        this.commandHandler.registerCommand(new WhoCommand(this));
        this.commandHandler.registerCommand(new SpawnCommand(this));
        // Dangerous Commands
        this.commandHandler.registerCommand(new UnloadCommand(this));
        this.commandHandler.registerCommand(new LoadCommand(this));
        this.commandHandler.registerCommand(new RemoveCommand(this));
        this.commandHandler.registerCommand(new DeleteCommand(this));
        this.commandHandler.registerCommand(new RegenCommand(this));
        this.commandHandler.registerCommand(new ConfirmCommand(this));
        // Modification commands
        this.commandHandler.registerCommand(new ModifyCommand(this));
        this.commandHandler.registerCommand(new PurgeCommand(this));
        this.commandHandler.registerCommand(new ModifyAddCommand(this));
        this.commandHandler.registerCommand(new ModifySetCommand(this));
        this.commandHandler.registerCommand(new ModifyRemoveCommand(this));
        this.commandHandler.registerCommand(new ModifyClearCommand(this));
        this.commandHandler.registerCommand(new ConfigCommand(this));
        this.commandHandler.registerCommand(new AnchorCommand(this));
        // Misc Commands
        this.commandHandler.registerCommand(new EnvironmentCommand(this));
        this.commandHandler.registerCommand(new DebugCommand(this));
        this.commandHandler.registerCommand(new GeneratorCommand(this));
        this.commandHandler.registerCommand(new CheckCommand(this));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDisable() {
        debugLog.close();
        this.banker = null;
        this.bank = null;
        log(Level.INFO, "- Disabled");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MVPlayerSession getPlayerSession(Player player) {
        if (this.playerSessions.containsKey(player.getName())) {
            return this.playerSessions.get(player.getName());
        } else {
            this.playerSessions.put(player.getName(), new MVPlayerSession(player, this.multiverseConfig, this));
            return this.playerSessions.get(player.getName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SafeTTeleporter getTeleporter() {
        return new SafeTTeleporter(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MVPermissions getMVPerms() {
        return this.ph;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
        if (!this.isEnabled()) {
            sender.sendMessage("This plugin is Disabled!");
            return true;
        }
        ArrayList<String> allArgs = new ArrayList<String>(Arrays.asList(args));
        allArgs.add(0, command.getName());
        return this.commandHandler.locateAndRunCommand(sender, allArgs, DisplayPermErrors);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void log(Level level, String msg) {
        staticLog(level, msg);
    }

    public static void staticLog(Level level, String msg) {
        if (level == Level.FINE && GlobalDebug >= 1) {
            staticDebugLog(Level.INFO, msg);
            return;
        } else if (level == Level.FINER && GlobalDebug >= 2) {
            staticDebugLog(Level.INFO, msg);
            return;
        } else if (level == Level.FINEST && GlobalDebug >= 3) {
            staticDebugLog(Level.INFO, msg);
            return;
        } else if (level != Level.FINE && level != Level.FINER && level != Level.FINEST) {
            log.log(level, tag + " " + msg);
            debugLog.log(level, tag + " " + msg);
        }
    }

    /**
     * Print messages to the Debug Log, if the servers in Debug Mode then we also wan't to print the messages to the
     * standard Server Console.
     *
     * @param level The Log-{@link Level}
     * @param msg The message
     */
    public static void staticDebugLog(Level level, String msg) {
        log.log(level, "[MVCore-Debug] " + msg);
        debugLog.log(level, "[MVCore-Debug] " + msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAuthors() {
        String authors = "";
        ArrayList<String> auths = this.getDescription().getAuthors();
        if (auths.size() == 0) {
            return "";
        }

        if (auths.size() == 1) {
            return auths.get(0);
        }

        for (int i = 0; i < auths.size(); i++) {
            if (i == this.getDescription().getAuthors().size() - 1) {
                authors += " and " + this.getDescription().getAuthors().get(i);
            } else {
                authors += ", " + this.getDescription().getAuthors().get(i);
            }
        }
        return authors.substring(2);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandHandler getCommandHandler() {
        return this.commandHandler;
    }

    /**
     * Gets the log-tag.
     *
     * @return The log-tag
     */
    // TODO this should be static!
    public String getTag() {
        return MultiverseCore.tag;
    }

    // TODO This code should get moved somewhere more appropriate, but for now, it's here.
    // TODO oh, and it should be static.
    /**
     * Converts a {@link String} into an {@link Environment}.
     *
     * @param env The environment as {@link String}
     * @return The environment as {@link Environment}
     */
    public Environment getEnvFromString(String env) {
        // Don't reference the enum directly as there aren't that many, and we can be more forgiving to users this way
        if (env.equalsIgnoreCase("HELL") || env.equalsIgnoreCase("NETHER"))
            env = "NETHER";

        if (env.equalsIgnoreCase("END") || env.equalsIgnoreCase("THEEND") || env.equalsIgnoreCase("STARWARS"))
            env = "THE_END";

        if (env.equalsIgnoreCase("NORMAL") || env.equalsIgnoreCase("WORLD"))
            env = "NORMAL";

        try {
            // If the value wasn't found, maybe it's new, try checking the enum directly.
            return Environment.valueOf(env);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Shows a message that the given world is not a MultiverseWorld.
     *
     * @param sender The {@link CommandSender} that should receive the message
     * @param worldName The name of the invalid world
     */
    public void showNotMVWorldMessage(CommandSender sender, String worldName) {
        sender.sendMessage("Multiverse doesn't know about " + ChatColor.DARK_AQUA + worldName + ChatColor.WHITE + " yet.");
        sender.sendMessage("Type " + ChatColor.DARK_AQUA + "/mv import ?" + ChatColor.WHITE + " for help!");
    }

    public void removePlayerSession(Player player) {
        if (this.playerSessions.containsKey(player.getName())) {
            this.playerSessions.remove(player.getName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPluginCount() {
        return this.pluginCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementPluginCount() {
        this.pluginCount += 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementPluginCount() {
        this.pluginCount -= 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AllPay getBanker() {
        return this.banker;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBank(GenericBank bank) {
        this.bank = bank;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DestinationFactory getDestFactory() {
        return this.destFactory;
    }

    /**
     * This is a convenience method to allow the QueuedCommand system to call it. You should NEVER call this directly.
     *
     * @param teleporter The Person requesting that the teleport should happen.
     * @param p          Player The Person being teleported.
     * @param l          The potentially unsafe location.
     */
    public void teleportPlayer(CommandSender teleporter, Player p, Location l) {
        // This command is the override, and MUST NOT TELEPORT SAFELY
        this.getTeleporter().safelyTeleport(teleporter, p, l, false);
    }

    /**
     * Gets the server's root-folder as {@link File}.
     *
     * @return The server's root-folder
     */
    public File getServerFolder() {
        return serverFolder;
    }

    /**
     * Sets this server's root-folder.
     *
     * @param newServerFolder The new server-root
     */
    public void setServerFolder(File newServerFolder) {
        if (!newServerFolder.isDirectory())
            throw new IllegalArgumentException("That's not a folder!");

        this.serverFolder = newServerFolder;
    }

    public void setSpout() {
        this.spoutInterface = new SpoutInterface();
        this.commandHandler.registerCommand(new SpoutCommand(this));
    }

    public SpoutInterface getSpout() {
        return this.spoutInterface;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MVWorldManager getMVWorldManager() {
        return this.worldManager;
    }

    /**
     * Gets the {@link MVPlayerListener}.
     *
     * @return The {@link MVPlayerListener}.
     */
    public MVPlayerListener getPlayerListener() {
        return this.playerListener;
    }

    // TODO remove this
    public boolean loadMVConfigs() {
        return false;
    }

    /**
     * Saves the Multiverse-Config.
     *
     * @return Whether the Multiverse-Config was successfully saved
     */
    public boolean saveMVConfig() {
        try {
            this.multiverseConfig.save(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (IOException e) {
            this.log(Level.SEVERE, "Could not save Multiverse config.yml config. Please check your file permissions.");
            return false;
        }
    }

    /**
     * Saves the world config.
     *
     * @return Whether the world-config was successfully saved
     */
    public boolean saveWorldConfig() {
        return this.worldManager.saveWorldsConfig();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveMVConfigs() {
        return this.saveMVConfig() && this.saveWorldConfig();
    }

    /**
     * NOT deprecated for the time as queued commands use this.
     * However, this is not in the API and other plugins should therefore not use it.
     *
     * @param name World to delete
     * @return True if success, false if fail.
     */
    public Boolean deleteWorld(String name) {
        return this.worldManager.deleteWorld(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean regenWorld(String name, Boolean useNewSeed, Boolean randomSeed, String seed) {
        MultiverseWorld world = this.worldManager.getMVWorld(name);
        if (world == null) {
            return false;
        }

        List<Player> ps = world.getCBWorld().getPlayers();

        if (useNewSeed) {
            // Set the worldseed.
            if (randomSeed) {
                Random random = new Random();
                Long newseed = random.nextLong();
                seed = newseed.toString();
            }
            ((WorldManager) this.worldManager).getConfigWorlds().set("worlds." + name + ".seed", seed);
        }
        if (this.worldManager.deleteWorld(name, false)) {
            this.worldManager.loadWorlds(false);
            SafeTTeleporter teleporter = this.getTeleporter();
            Location newSpawn = this.getServer().getWorld(name).getSpawnLocation();
            // Send all players that were in the old world, BACK to it!
            for (Player p : ps) {
                teleporter.safelyTeleport(null, p, newSpawn, true);
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AnchorManager getAnchorManager() {
        return this.anchorManager;
    }

}
