package com.shuzhi.websocket.socketvo;

import com.shuzhi.light.entities.TEvent;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author zgk
 * @description 照明统计信息
 * @date 2019-07-31 10:32
 */
@Data
public class LightMsgVo {

    /**
     * 总数
     */
    private Integer total;

    /**
     * 在线
     */
    private Integer online;

    /**
     * 离线
     */
    private Integer offline;

    /**
     * 亮灯数
     */
    private Integer oncount;

    /**
     * 熄灯数
     */
    private Integer offcount;

    /**
     * 报警次数
     */
    private List<Lightalarms> lightalarms = new ArrayList<>();

    public LightMsgVo(StatisticsMsgVo statisticsMsgVo, List<TEvent> event) {

        this.total = statisticsMsgVo.getTotal();
        this.offcount = statisticsMsgVo.getOffcount();
        this.online = statisticsMsgVo.getOnline();
        this.oncount = statisticsMsgVo.getOncount();
        this.offline = statisticsMsgVo.getOffline();

        AtomicInteger order = new AtomicInteger(1);
        event.forEach(tEvent -> {

            Lightalarms lightalarm = new Lightalarms(tEvent.getCount(),tEvent.getCreatetime(),order.get());
            lightalarms.add(lightalarm);
            order.getAndIncrement();
        });

    }
}
