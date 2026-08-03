package com.caopan.platform.geo.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.geo.entity.PlatformAdminOperationLog;
import com.caopan.platform.geo.mapper.PlatformAdminOperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 管理端统一操作审计（写失败不阻断主流程）。
 */
@Service
public class AdminOperationLogService {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int SUMMARY_MAX = 2000;
    private static final int SNAPSHOT_MAX = 8000;

    private final PlatformAdminOperationLogMapper mapper;

    public AdminOperationLogService(PlatformAdminOperationLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(RecordRequest req) {
        if (req == null || !StringUtils.hasText(req.module()) || !StringUtils.hasText(req.action())) {
            return;
        }
        try {
            PlatformAdminOperationLog row = new PlatformAdminOperationLog();
            row.setModule(trim(req.module(), 32));
            row.setAction(trim(req.action(), 32));
            row.setResourceType(trim(StringUtils.hasText(req.resourceType()) ? req.resourceType() : "unknown", 64));
            row.setResourceId(trim(req.resourceId(), 128));
            row.setRequestSummary(trim(req.requestSummary(), SUMMARY_MAX));
            row.setBeforeData(trim(req.beforeData(), SNAPSHOT_MAX));
            row.setAfterData(trim(req.afterData(), SNAPSHOT_MAX));
            row.setSuccess(req.success() ? 1 : 0);
            row.setErrorMsg(trim(req.errorMsg(), 512));
            row.setOperator(trim(req.operator(), 64));
            row.setOperatorId(req.operatorId());
            row.setClientIp(trim(req.clientIp(), 64));
            row.setCostMs(req.costMs());
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("admin operation log insert failed: {}", e.getMessage());
        }
    }

    public PageResult<OpLogView> page(
            String module,
            String operator,
            String keyword,
            String from,
            String to,
            int pageNum,
            int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);

        LambdaQueryWrapper<PlatformAdminOperationLog> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(module)) {
            q.eq(PlatformAdminOperationLog::getModule, module.trim());
        }
        if (StringUtils.hasText(operator)) {
            q.like(PlatformAdminOperationLog::getOperator, operator.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(PlatformAdminOperationLog::getResourceId, kw)
                    .or().like(PlatformAdminOperationLog::getRequestSummary, kw)
                    .or().like(PlatformAdminOperationLog::getAction, kw)
                    .or().like(PlatformAdminOperationLog::getResourceType, kw));
        }
        LocalDateTime fromTs = parseTs(from);
        LocalDateTime toTs = parseTs(to);
        if (fromTs != null) {
            q.ge(PlatformAdminOperationLog::getCreatedAt, fromTs);
        }
        if (toTs != null) {
            q.le(PlatformAdminOperationLog::getCreatedAt, toTs);
        }
        q.orderByDesc(PlatformAdminOperationLog::getId);

        Long total = mapper.selectCount(q);
        long t = total == null ? 0L : total;
        q.last("LIMIT " + ((pn - 1) * ps) + "," + ps);
        List<PlatformAdminOperationLog> rows = mapper.selectList(q);
        return PageResult.of(t, pn, ps, rows.stream().map(this::toView).toList());
    }

    private OpLogView toView(PlatformAdminOperationLog r) {
        return new OpLogView(
                r.getId(),
                r.getModule(),
                r.getAction(),
                r.getResourceType(),
                r.getResourceId(),
                r.getRequestSummary(),
                r.getBeforeData(),
                r.getAfterData(),
                r.getSuccess() != null && r.getSuccess() == 1,
                r.getErrorMsg(),
                r.getOperator(),
                r.getOperatorId(),
                r.getClientIp(),
                r.getCostMs(),
                r.getCreatedAt());
    }

    private static LocalDateTime parseTs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim().replace('T', ' ');
        if (s.length() == 10) {
            s = s + " 00:00:00";
        }
        try {
            return LocalDateTime.parse(s, TS);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s);
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    public record RecordRequest(
            String module,
            String action,
            String resourceType,
            String resourceId,
            String requestSummary,
            String beforeData,
            String afterData,
            boolean success,
            String errorMsg,
            String operator,
            Long operatorId,
            String clientIp,
            Integer costMs
    ) {
        public static RecordRequest ok(
                String module, String action, String resourceType, String resourceId,
                String summary, String before, String after,
                String operator, Long operatorId, String clientIp, int costMs) {
            return new RecordRequest(
                    module, action, resourceType, resourceId,
                    summary, before, after, true, null,
                    operator, operatorId, clientIp, costMs);
        }

        public static RecordRequest fail(
                String module, String action, String resourceType, String resourceId,
                String summary, String error,
                String operator, Long operatorId, String clientIp, int costMs) {
            return new RecordRequest(
                    module, action, resourceType, resourceId,
                    summary, null, null, false, error,
                    operator, operatorId, clientIp, costMs);
        }
    }

    public record OpLogView(
            Long id,
            String module,
            String action,
            String resourceType,
            String resourceId,
            String requestSummary,
            String beforeData,
            String afterData,
            boolean success,
            String errorMsg,
            String operator,
            Long operatorId,
            String clientIp,
            Integer costMs,
            LocalDateTime createdAt
    ) {
    }
}
