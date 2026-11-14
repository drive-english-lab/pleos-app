package io.github.driveenglishlab.pleos.data

/** Register these entities plus ReviewDao on the next centrally owned preserving Room migration.
 * Existing LocalOwner/LearningDao and SegmentPlayback entities/DAO are reused, never recreated.
 * Database entities: LocalReviewContext, LocalReviewOperation, LocalReviewObservation,
 * LocalFreshPracticeIntent, LocalPreparationWaiter, LocalSettingsDraft, LocalSource6Record.
 * Database accessor: abstract fun review(): ReviewDao
 */
object ReviewRoomRegistration {
    val createSql:List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `review_context` (`profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `selectionId` TEXT NOT NULL, `version` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, PRIMARY KEY(`profileId`,`privacyEpoch`,`selectionId`))",
        "CREATE TABLE IF NOT EXISTS `review_operation` (`operationId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `ownerRevision` INTEGER NOT NULL, `kind` TEXT NOT NULL, `targetId` TEXT, `payloadJson` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, `status` TEXT NOT NULL, `receiptJson` TEXT, PRIMARY KEY(`operationId`))",
        "CREATE INDEX IF NOT EXISTS `index_review_operation_profileId_privacyEpoch_status` ON `review_operation` (`profileId`,`privacyEpoch`,`status`)",
        "CREATE TABLE IF NOT EXISTS `review_observation` (`profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `deviceStreamId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `observationId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, PRIMARY KEY(`profileId`,`privacyEpoch`,`deviceStreamId`,`sequence`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_observation_observationId` ON `review_observation` (`observationId`)",
        "CREATE TABLE IF NOT EXISTS `fresh_practice_intent` (`operationId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `ownerRevision` INTEGER NOT NULL, `sourceLessonId` TEXT NOT NULL, `newLessonId` TEXT NOT NULL, `expectedResumeVersion` INTEGER NOT NULL, `pinnedRefJson` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`operationId`))",
        "CREATE TABLE IF NOT EXISTS `preparation_waiter` (`requestId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `ownerRevision` INTEGER NOT NULL, `contextToken` TEXT NOT NULL, `desiredAction` TEXT NOT NULL, `requestJson` TEXT NOT NULL, `requestHash` TEXT NOT NULL, `requestVersion` INTEGER NOT NULL, `responseJson` TEXT, `cancelled` INTEGER NOT NULL, PRIMARY KEY(`requestId`))",
        "CREATE TABLE IF NOT EXISTS `settings_draft` (`profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `ownerRevision` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `baseSettingsVersion` INTEGER NOT NULL, `originalJson` TEXT NOT NULL, `draftJson` TEXT NOT NULL, `previewToken` TEXT, `savedOperationId` TEXT, PRIMARY KEY(`profileId`,`privacyEpoch`))",
        "CREATE TABLE IF NOT EXISTS `source6_record` (`profileId` TEXT NOT NULL, `privacyEpoch` INTEGER NOT NULL, `kind` TEXT NOT NULL, `recordId` TEXT NOT NULL, `version` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, PRIMARY KEY(`profileId`,`privacyEpoch`,`kind`,`recordId`))",
    )
}
