package io.github.driveenglishlab.pleos.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing payload bytes/hash and owner isolation survive; no guessed evidence is backfilled. */
val LEARNING_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE learning_record ADD COLUMN payloadSchemaVersion INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE learning_outbox ADD COLUMN payloadSchemaVersion INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE learning_outbox ADD COLUMN transport TEXT NOT NULL DEFAULT 'legacy'")
        db.execSQL("CREATE TABLE IF NOT EXISTS history_coverage (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, origin TEXT NOT NULL, version INTEGER NOT NULL, historyGap INTEGER NOT NULL, PRIMARY KEY(profileId, privacyEpoch))")
        db.execSQL("INSERT INTO history_coverage SELECT profileId, privacyEpoch, 'legacy_or_incomplete', 1, 1 FROM learning_record GROUP BY profileId, privacyEpoch")
        db.execSQL("INSERT OR IGNORE INTO history_coverage SELECT profileId, privacyEpoch, 'legacy_or_incomplete', 1, 1 FROM learning_outbox GROUP BY profileId, privacyEpoch")
        db.execSQL("INSERT OR IGNORE INTO history_coverage SELECT profileId, privacyEpoch, 'legacy_or_incomplete', 1, 1 FROM local_owner")
        db.execSQL("CREATE TABLE IF NOT EXISTS practice_binding (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, lessonId TEXT NOT NULL, articleId TEXT NOT NULL, basicQuestionVersionId TEXT NOT NULL, taskId TEXT NOT NULL, hintMethodAnnounced INTEGER NOT NULL, PRIMARY KEY(profileId, privacyEpoch, lessonId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS presentation_ticket (presentationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, contextJson TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_presentation_ticket_profileId_privacyEpoch ON presentation_ticket(profileId, privacyEpoch)")
        db.execSQL("CREATE TABLE IF NOT EXISTS presentation_observation (observationId TEXT NOT NULL PRIMARY KEY, presentationId TEXT NOT NULL, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, outputInstanceId TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_presentation_observation_profileId_privacyEpoch ON presentation_observation(profileId, privacyEpoch)")
        db.execSQL("CREATE TABLE IF NOT EXISTS presentation_receipt (operationId TEXT NOT NULL PRIMARY KEY, observationId TEXT NOT NULL, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dialogue_completion (outputInstanceId TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, lessonId TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dialogue_completion_eventId ON dialogue_completion(eventId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dialogue_completion_profileId_privacyEpoch_lessonId ON dialogue_completion(profileId, privacyEpoch, lessonId)")
    }
}
