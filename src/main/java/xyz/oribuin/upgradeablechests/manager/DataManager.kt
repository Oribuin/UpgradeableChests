package xyz.oribuin.upgradeablechests.manager

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import xyz.oribuin.orilibrary.database.DatabaseConnector
import xyz.oribuin.orilibrary.database.MySQLConnector
import xyz.oribuin.orilibrary.database.SQLiteConnector
import xyz.oribuin.orilibrary.manager.Manager
import xyz.oribuin.orilibrary.util.FileUtils
import xyz.oribuin.upgradeablechests.UpgradeableChests
import xyz.oribuin.upgradeablechests.migration._1_CreateTables
import xyz.oribuin.upgradeablechests.obj.Chest
import xyz.oribuin.upgradeablechests.obj.Tier
import xyz.oribuin.upgradeablechests.util.PluginUtils.handleDeserialization
import xyz.oribuin.upgradeablechests.util.PluginUtils.handleSerialization
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer


class DataManager(private val plugin: UpgradeableChests) : Manager(plugin) {

    private var connector: DatabaseConnector? = null
    private val cachedChests = mutableMapOf<Int, Chest>()

    private lateinit var tablePrefix: String

    override fun enable() {
        val config = this.plugin.config
        this.tablePrefix = config.getString("mysql.table-prefix") ?: "upgradeablechests_"

        if (config.getBoolean("mysql.enabled")) {
            val hostname = config.getString("mysql.host")!!
            val port = config.getInt("mysql.port")
            val dbname = config.getString("mysql.dbname")!!
            val username = config.getString("mysql.username")!!
            val password = config.getString("mysql.password")!!
            val ssl = config.getBoolean("mysql.ssl")

            // Connect to MySQL
            connector = MySQLConnector(this.plugin, hostname, port, dbname, username, password, ssl)
            this.plugin.logger.info("Using MySQL For Database Saving!")
        } else {
            FileUtils.createFile(this.plugin, "database.db")

            // Connect to SQLite
            connector = SQLiteConnector(this.plugin, "database.db")
            this.plugin.logger.info("Using SQLite for Database Saving!")
        }

        // Disable plugin if connector is still null
        if (connector == null) {
            this.plugin.logger.severe("Unable to connect to MySQL or SQLite, Disabling plugin...")
            this.plugin.server.pluginManager.disablePlugin(this.plugin)
            return
        }

        async {
            connector?.connect { connection ->
                _1_CreateTables(tablePrefix).migrate(connector, connection)
            }
            cacheChests()
        }

    }

    /**
     * Cache all the plugin's upgradeable chests
     * @since 1.0
     */
    private fun cacheChests() {

        // this also feels like a mess
        val chests = mutableListOf<Chest>()

        CompletableFuture.runAsync {
            connector?.connect { connection ->
                val query = "SELECT * FROM " + tablePrefix + "chests"

                connection.prepareStatement(query).use { statement ->
                    val resultSet = statement.executeQuery()

                    while (resultSet.next()) {
                        val id = resultSet.getInt("chestID")
                        val tier = resultSet.getInt("tier")
                        val loc = Location(
                            Bukkit.getWorld(resultSet.getString("world")),
                            resultSet.getDouble("x"),
                            resultSet.getDouble("y"),
                            resultSet.getDouble("z")
                        )

                        val chest = Chest(id, plugin.getManager(TierManager::class.java).getTier(tier), loc)
                        val newQuery = "SELECT item FROM " + tablePrefix + "items WHERE chestID = ?"

                        // like holy fuck what is going on here, two??? while loops, thats asking for the server to have a mental breakdown
                        val items = mutableListOf<ItemStack>()
                        try {

                            connection.prepareStatement(newQuery).use { newStatement ->
                                val newResult = newStatement.executeQuery()
                                while (newResult.next()) {
                                    items.add(handleDeserialization(newResult.getString(1)) ?: ItemStack(Material.AIR))
                                }
                            }

                        } finally {
                            chest.items.addAll(items)
                            chests.add(chest)
                        }
                    }
                }
            }

        }.thenRunAsync {
            chests.forEach { cachedChests[it.id] = it }
        }

    }

    /**
     * Create a new [Chest] object and save it into the database.
     * @since 1.0
     *
     * @param tier The tier of the [Chest]
     * @param location The location of the [Chest]
     * @return a nullable [Chest]
     */
    fun createChest(id: Int, tier: Tier, location: Location): Chest {

        val chest = Chest(id, tier, location)
        this.cachedChests[id] = chest
        async { _ ->

            connector?.connect { connection ->
                val query = "REPLACE INTO " + tablePrefix + "chests (chestID, tier, x, y, z, world) VALUES (?, ?, ?, ?, ?, ?)"

                connection.prepareStatement(query).use {
                    it.setInt(1, getNextChestID(cachedChests.map { chest -> chest.value.id }.toList()))
                    it.setInt(2, tier.id)
                    it.setDouble(3, location.x)
                    it.setDouble(4, location.y)
                    it.setDouble(5, location.z)
                    it.setString(6, location.world?.name)
                    it.executeUpdate()
                }

            }
        }

        return chest
    }

    /**
     * Get a nullable [Chest] from a [Location].
     * @since 1.0
     *
     * @param loc The [Location]
     */
    fun getChest(loc: Location): Chest? {
        return this.cachedChests.filter { it.value.location == loc }[0]
    }

    /**
     * Save the [Chest] into the database and cache it.
     * @since 1.0
     *
     * @param chest The [Chest]
     */
    fun saveChest(chest: Chest) {
        cachedChests[chest.id] = chest
        async {

            connector?.connect { connection ->

                connection.prepareStatement("DELETE FROM " + tablePrefix + "items" + "WHERE chestID = ?").use { statement ->
                    statement.setInt(1, chest.id)
                    statement.execute()
                }

                // Save the chest in the chests table
                val addChest = "REPLACE INTO " + tablePrefix + "chests (tier, x, y, z, world) VALUES (?, ?, ?, ?, ?)"
                connection.prepareStatement(addChest).use { statement ->
                    statement.setInt(1, chest.tier.id)
                    statement.setDouble(2, chest.location.x)
                    statement.setDouble(3, chest.location.y)
                    statement.setDouble(4, chest.location.z)
                    statement.setString(5, chest.location.world!!.name)
                    statement.execute()
                }

                connection.createStatement().use { statement ->
                    chest.items.stream()
                        .map { handleSerialization(it) }
                        .forEach { statement.addBatch("INSERT INTO " + tablePrefix + "items(chestID, item) VALUES(" + chest.id + ", \"" + it + "\"") }

                    statement.executeBatch()
                }

            }
        }
    }

    /**
     * Delete a chest from the database and removeit from the cache
     * @since 1.0
     *
     * @id The id of the [Chest]
     */
    fun deleteChest(id: Int) {
        cachedChests.remove(id)

        async {

            this.connector?.connect { connection ->
                connection.prepareStatement("DELETE FROM ${tablePrefix}items WHERE chestID = ?").use {
                    it.setInt(1, id)
                    it.execute()
                }

                connection.prepareStatement("DELETE FROM ${tablePrefix}chests WHERE chestID = ?").use {
                    it.setInt(1, id)
                    it.execute()
                }

            }
        }

    }

    /**
     * @author Esophose
     *
     * Gets the smallest positive integer greater than 0 from a list
     *
     * @param existingIds The list containing non-available ids
     * @return The smallest positive integer not in the given list
     */
    fun getNextChestID(existingIds: Collection<Int>): Int {
        val copy = existingIds.sorted().toMutableList()
        copy.removeIf { it <= 0 }

        var current = 1
        for (i in copy) {
            if (i == current) {
                current++
            } else break
        }

        return current
    }

    override fun disable() {
        if (connector != null) {
            connector!!.closeConnection()
        }
    }

    private fun async(callback: Consumer<BukkitTask>) {
        this.plugin.server.scheduler.runTaskAsynchronously(plugin, callback)
    }
}