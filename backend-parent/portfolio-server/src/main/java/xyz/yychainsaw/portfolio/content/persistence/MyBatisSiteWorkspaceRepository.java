package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.mybatis.SiteWorkspaceMapper;

@Repository
public class MyBatisSiteWorkspaceRepository implements SiteWorkspaceRepository {
    private final SiteWorkspaceMapper mapper;
    private final SiteWorkspaceAssembler assembler;
    private final Clock clock;

    MyBatisSiteWorkspaceRepository(
            SiteWorkspaceMapper mapper,
            SiteWorkspaceAssembler assembler,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "site workspace mapper is required");
        this.assembler = Objects.requireNonNull(assembler, "site workspace assembler is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SiteWorkspaceDto require() {
        return assembler.load(mapper, SiteWorkspaceDto.SITE_ID);
    }

    @Override
    @Transactional
    public void replace(SiteWorkspaceDto workspace, long expectedVersion) {
        replaceAt(workspace, expectedVersion, clock.instant());
    }

    @Override
    @Transactional
    public void replace(SiteWorkspaceDto workspace, long expectedVersion, Instant updatedAt) {
        replaceAt(workspace, expectedVersion, updatedAt);
    }

    private void replaceAt(
            SiteWorkspaceDto workspace,
            long expectedVersion,
            Instant updatedAt) {
        Objects.requireNonNull(workspace, "site workspace is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (!SiteWorkspaceDto.SITE_ID.equals(workspace.siteId())) {
            throw ContentPersistenceErrors.identityMismatch();
        }
        assembler.validate(workspace);
        try {
            int changed = mapper.updateRoot(
                    SiteWorkspaceDto.SITE_ID,
                    workspace.monogram(),
                    workspace.email(),
                    expectedVersion,
                    updatedAt);
            if (changed != 1) {
                if (mapper.selectProfile(SiteWorkspaceDto.SITE_ID) == null) {
                    throw ContentPersistenceErrors.siteMissing();
                }
                throw ContentPersistenceErrors.versionConflict();
            }
            assembler.replaceChildren(mapper, workspace);
        } catch (RuntimeException exception) {
            throw ContentPersistenceErrors.translateConstraint(exception);
        }
    }
}
