package com.shuzhi.websocket.socketvo;

import com.shuzhi.entity.DeviceLoop;
import com.shuzhi.led.entities.TStatusDto;
import com.shuzhi.light.entities.TLoopStateDto;
import com.shuzhi.service.DeviceLoopService;
import com.shuzhi.websocket.ApplicationContextUtils;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @ProjectName: bus-station-web
 * @Package: com.shuzhi.websocket.socketvo
 * @ClassName: StatisticsMsgVo统计能耗实体类
 * @Author: 陈鑫晖
 * @Date: 2019/7/19 14:57
 */
@Data
public class StatisticsMsgVo {

    /**
     * 总数
     */
    private Integer total = 0;

    /**
     * 在线
     */
    private Integer online = 0;

    /**
     * 离线
     */
    private Integer offline = 0;

    /**
     * 开关状态
     */
    private Integer onoff = 0;

    /**
     * 亮灯数
     */
    private Integer oncount = 0;

    /**
     * 熄灯数
     */
    private Integer offcount = 0;

    /**
     * 本月
     */
    private float currentmonth = 0;

    /**
     * 上月
     */
    private float lastmonth = 0;

    /**
     * 本年
     */
    private float thisyear = 0;

    /**
     * 当前
     */
    private float activepowerNow = 0;

    private List<LcdalarmsVo> lcdalarms;

    private List<LcdalarmsVo> ledalarms;

    private List<LcdalarmsVo> lightalarms;


    public StatisticsMsgVo(float currentmonth, float lastmonth, float thisyear ,float activepowerNow) {
        this.currentmonth = currentmonth;
        this.lastmonth = lastmonth;
        this.thisyear = thisyear;
        this.activepowerNow = activepowerNow;
    }
    public StatisticsMsgVo() {
    }

    public StatisticsMsgVo(float currentmonth, float lastmonth, float thisyear) {

        this.currentmonth = currentmonth;
        this.lastmonth = lastmonth;
        this.thisyear = thisyear;

    }

    public StatisticsMsgVo(StatisticsMsgVo statisticsMsgVo1, StatisticsMsgVo statisticsMsgVo2, StatisticsMsgVo statisticsMsgVo3) {

        this.setActivepowerNow(statisticsMsgVo1.getActivepowerNow() + statisticsMsgVo2.getActivepowerNow() + statisticsMsgVo3.getActivepowerNow());
        this.setCurrentmonth(statisticsMsgVo1.getCurrentmonth() + statisticsMsgVo2.getCurrentmonth() + statisticsMsgVo3.getCurrentmonth());
        this.setLastmonth(statisticsMsgVo1.getLastmonth() + statisticsMsgVo2.getLastmonth() + statisticsMsgVo3.getLastmonth());
        this.setThisyear(statisticsMsgVo1.getThisyear() + statisticsMsgVo2.getThisyear() + statisticsMsgVo3.getThisyear());
    }

    public void addNum(List<TStatusDto> allStatus) {

        this.total = allStatus.size();
        this.online = Math.toIntExact(allStatus.stream().filter(tStatusDto -> tStatusDto.getOnoff() == 1).count());
        this.offline = Math.toIntExact(allStatus.stream().filter(tStatusDto -> tStatusDto.getOnoff() == 0).count());

    }
    public void addLightNum(List<TLoopStateDto> tLoopStateDtos) {

        //过滤出照明设备
        DeviceLoopService deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class);
        DeviceLoop deviceLoopSelect = new DeviceLoop();
        List<TLoopStateDto> collect = tLoopStateDtos.stream().filter(loopStateDto -> {
            deviceLoopSelect.setLoop(loopStateDto.getLoop());
            deviceLoopSelect.setGatewayDid(loopStateDto.getGatewayId());
            DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
            //判断这个设备是不是照明设备
            return "1".equals(deviceLoop.getTypecode()) || "2".equals(deviceLoop.getTypecode()) || "3".equals(deviceLoop.getTypecode());
        }).collect(Collectors.toList());


        this.total = collect.size();
        this.online = Math.toIntExact(collect.stream().filter(tStatusDto -> tStatusDto.getState() == 1).count());
        this.offline = Math.toIntExact(collect.stream().filter(tStatusDto -> tStatusDto.getState() == 0).count());
        this.oncount = online;
        this.offcount = offline;

    }
}
