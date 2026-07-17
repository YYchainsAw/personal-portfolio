package xyz.yychainsaw.portfolio.media.application;

public interface MediaImportService {
    /**
     * Imports a repository-local file into staged media. The caller must hold the
     * same active transaction that will persist its content revision; rollback
     * deliberately leaves only the provider staging object for durable cleanup.
     */
    ImportedMedia importLocal(ImportMediaCommand command);
}
