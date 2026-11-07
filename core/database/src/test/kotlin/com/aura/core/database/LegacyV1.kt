package com.aura.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Builds a database exactly as Aura v0.1 (Room schema version 1) left it on a phone. */
object LegacyV1 {
    private val ddl = listOf(
        "CREATE TABLE `messages` (`id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `metadata` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `memories` (`id` TEXT NOT NULL, `content` TEXT NOT NULL, `embeddingBlob` BLOB NOT NULL, `type` TEXT NOT NULL, `importance` REAL NOT NULL, `createdAtMs` INTEGER NOT NULL, `accessedAtMs` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `tags` TEXT NOT NULL, `linkedGoalId` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE `goals` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `priority` INTEGER NOT NULL, `deadlineMs` INTEGER, `progress` REAL NOT NULL, `isActive` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `action_log` (`actionId` TEXT NOT NULL, `tool` TEXT NOT NULL, `success` INTEGER NOT NULL, `output` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, PRIMARY KEY(`actionId`))",
        "CREATE TABLE `knowledge_nodes` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `type` TEXT NOT NULL, `propertiesJson` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `knowledge_edges` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `relation` TEXT NOT NULL, `weight` REAL NOT NULL, PRIMARY KEY(`fromId`, `toId`, `relation`))",
    )

    fun create(context: Context, name: String, withHistory: Boolean = true) {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        try {
            ddl.forEach(db::execSQL)
            if (withHistory) {
                db.execSQL("INSERT INTO messages VALUES ('m1','USER','Bonjour',1000,'{}'), ('m2','ASSISTANT','Salut !',2000,'{}'), ('m3','USER','Merci',3000,'{}')")
                db.execSQL("INSERT INTO memories VALUES ('s1','J''aime le café',x'00000000',  'SEMANTIC',0.7,10,20,3,'a,b','goal1')")
                db.execSQL("INSERT INTO memories VALUES ('e1','User: salut Aura: bonjour',x'00000000','EPISODIC',0.6,30,40,1,'',NULL)")
            }
            db.execSQL("INSERT INTO goals VALUES ('g1','Courir','10 km','HEALTH',5,NULL,0.25,1,1,1)")
            db.execSQL("INSERT INTO action_log VALUES ('a1','CALENDAR_READ',1,'ok',5)")
            db.execSQL("INSERT INTO knowledge_nodes VALUES ('n1','Paris','ENTITY','{}')")
            db.version = 1
        } finally {
            db.close()
        }
    }
}
