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

/** Add separate feature storage. Existing rows, payload bytes and history evidence stay untouched. */
val LEARNING_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE daily_preference_cache (profileId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, payloadJson TEXT NOT NULL)")
        db.execSQL("CREATE TABLE daily_preference_journal (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, ownerRevision INTEGER NOT NULL, localSequence INTEGER NOT NULL, intentJson TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, state TEXT NOT NULL, isolated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX index_daily_preference_journal_profileId_isolated_localSequence ON daily_preference_journal(profileId, isolated, localSequence)")
        db.execSQL("CREATE TABLE speaking_records_cache (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, kind TEXT NOT NULL, cacheKey TEXT NOT NULL, revision INTEGER NOT NULL, payloadJson TEXT NOT NULL, PRIMARY KEY(profileId, privacyEpoch, kind, cacheKey))")
        db.execSQL("CREATE TABLE speaking_records_journal (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, kind TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, state TEXT NOT NULL, isolated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX index_speaking_records_journal_profileId_privacyEpoch_isolated ON speaking_records_journal(profileId, privacyEpoch, isolated)")
        db.execSQL("CREATE TABLE learning_preference_cache (profileId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, payloadJson TEXT NOT NULL)")
        db.execSQL("CREATE TABLE learning_preference_journal (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, ownerRevision INTEGER NOT NULL, localSequence INTEGER NOT NULL, intentJson TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, state TEXT NOT NULL, isolated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX index_learning_preference_journal_profileId_isolated_localSequence ON learning_preference_journal(profileId, isolated, localSequence)")
        db.execSQL("CREATE TABLE ai_learning_cache (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, kind TEXT NOT NULL, cacheKey TEXT NOT NULL, revision INTEGER NOT NULL, payloadJson TEXT NOT NULL, PRIMARY KEY(profileId, privacyEpoch, kind, cacheKey))")
        db.execSQL("CREATE TABLE ai_learning_journal (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, kind TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, state TEXT NOT NULL, isolated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX index_ai_learning_journal_profileId_privacyEpoch_isolated ON ai_learning_journal(profileId, privacyEpoch, isolated)")
    }
}

/** Additive review/segment state; legacy records and byte-identical outbox remain intact. */
val LEARNING_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ReviewRoomRegistration.createSql.forEach(db::execSQL)
        createSegmentPlaybackTables(db)
    }
}

/** Private read-cache/live-intent sidecars; no answer, stream, selection or settings rewrite. */
val LEARNING_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) { createServiceIntentTables(db) }
}
