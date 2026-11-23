package com.nacos.ncg.web;

import com.nacos.ncg.notification.app.NotifyBindingService;
import com.nacos.ncg.notification.domain.NotifyBinding;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知绑定 REST（契约 #38-40）：应用×环境×渠道（IN_APP / WECOM）。
 * target 恒脱敏 ●●●●●●●●（MUST-NOT-05）：只在写入时出现（落库即加密），响应永不回显；
 * 保存必须重新输入新值。列表参数 appId/envId 可选（契约无参=全量）。
 */
@RestController
@RequestMapping("/api/v1/notify-bindings")
public class NotifyBindingController {

    private final NotifyBindingService bindingService;

    public NotifyBindingController(NotifyBindingService bindingService) {
        this.bindingService = bindingService;
    }

    public record BindingRequest(@NotBlank String appId, @NotBlank String envId,
                                 @NotBlank String channel, String target) {}

    /** 契约 #38：GET /notify-bindings（ADMIN）。appId/envId 可选过滤。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String appId,
                                          @RequestParam(required = false) String envId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotifyBinding b : bindingService.listAll()) {
            if (appId != null && !appId.isBlank() && !appId.equals(b.getAppId())) continue;
            if (envId != null && !envId.isBlank() && !envId.equals(b.getEnvId())) continue;
            rows.add(ContractViews.notifyRow(b));
        }
        return rows;
    }

    /** 旧入口：body 自带 appId/envId。 */
    @PutMapping
    public Map<String, Object> upsert(@RequestBody BindingRequest request, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        NotifyBinding saved = bindingService.upsert(request.appId(), request.envId(),
                request.channel(), request.target(), actorId, null);
        return ContractViews.notifyRow(saved);
    }

    /** 契约 #39：PUT /notify-bindings/{appId}/{envId} body {channel,target}。 */
    @PutMapping("/{appId}/{envId}")
    public Map<String, Object> upsertByPath(@PathVariable String appId, @PathVariable String envId,
                                            @RequestBody BindingRequest request, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        NotifyBinding saved = bindingService.upsert(appId, envId,
                request.channel(), request.target(), actorId, null);
        return ContractViews.notifyRow(saved);
    }

    /** 契约 #40：DELETE /notify-bindings/{appId}/{envId}（幂等解绑）。
     *  channel 可选：给定=删该渠道（旧口径），缺省=删该应用×环境全部绑定。 */
    @DeleteMapping("/{appId}/{envId}")
    public Map<String, Object> deleteByPath(@PathVariable String appId, @PathVariable String envId,
                                            @RequestParam(required = false) String channel,
                                            Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        List<NotifyBinding> existing = bindingService.list(appId, envId).stream()
                .filter(b -> channel == null || channel.isBlank() || channel.equals(b.getChannel()))
                .toList();
        if (existing.isEmpty()) {
            // 幂等：无绑定可删也返回成功
            return Map.of("appId", appId, "envId", envId, "deleted", true, "count", 0);
        }
        for (NotifyBinding b : existing) {
            bindingService.delete(b.getBindingId(), actorId, null);
        }
        return Map.of("appId", appId, "envId", envId, "deleted", true, "count", existing.size());
    }

    /** 旧入口：按 bindingId 删。 */
    @DeleteMapping("/{bindingId}")
    public Map<String, Object> delete(@PathVariable String bindingId, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        bindingService.delete(bindingId, actorId, null);
        return Map.of("bindingId", bindingId, "deleted", true);
    }
}
