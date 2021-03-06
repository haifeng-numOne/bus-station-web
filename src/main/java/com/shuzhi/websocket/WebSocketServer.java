package com.shuzhi.websocket;

import com.alibaba.fastjson.JSON;
import com.shuzhi.entity.DeviceLoop;
import com.shuzhi.entity.DeviceStation;
import com.shuzhi.entity.MqMessage;
import com.shuzhi.entity.Station;
import com.shuzhi.lcd.entities.IotLcdStatusTwo;
import com.shuzhi.lcd.service.IotLcdsStatusService;
import com.shuzhi.lcd.service.TEventLcdService;
import com.shuzhi.led.entities.TStatusDto;
import com.shuzhi.led.service.TEventLedService;
import com.shuzhi.led.service.TStatusService;
import com.shuzhi.light.entities.StatisticsVo;
import com.shuzhi.light.entities.TEvent;
import com.shuzhi.light.entities.TLoopStateDto;
import com.shuzhi.light.service.LoopStatusServiceApi;
import com.shuzhi.mapper.DeviceLoopMapper;
import com.shuzhi.mapper.StationMapper;
import com.shuzhi.rabbitmq.Message;
import com.shuzhi.service.DeviceLoopService;
import com.shuzhi.service.DeviceStationService;
import com.shuzhi.service.MqMessageService;
import com.shuzhi.service.StationService;
import com.shuzhi.websocket.socketvo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author zgk
 * @description websocket
 * @date 2019-07-07 16:13
 */
@Slf4j
@ServerEndpoint("/websocket")
@Component
public class WebSocketServer {

    private static StringRedisTemplate redisTemplate;

    private MqMessageService mqMessageService;

    private TStatusService tStatusService;

    private LoopStatusServiceApi loopStatusServiceApi;

    private IotLcdsStatusService iotLcdStatusService;

    private DeviceLoopService deviceLoopService;

    private DeviceStationService deviceStationService;

    private StationService stationService;

    private DeviceLoopMapper deviceLoopMapper;

    private TEventLedService tEventLedService;

    private TEventLcdService tEventLcdService;

    private StationMapper stationMapper;

    private static Map<String, CopyOnWriteArrayList<Session>> SESSION_MAP = new ConcurrentHashMap<>();

    private static Hashtable<String, Integer> sessionCodeMap = new Hashtable<>();

    /**
     * lcd设备状态
     */
    private List<IotLcdStatusTwo> allStatusByRedis;

    /**
     * led设备状态
     */
    private List<TStatusDto> allStatus;

    /**
     * 照明设备状态
     */
    private List<TLoopStateDto> loopStatus;

    /**
     * 重试次数
     */
    @Value("${send.retry}")
    private Integer retry;

    /**
     * 当前重试次数
     */
    private int count = 0;

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session) {

    }


    /**
     * 接收消息的方法
     *
     * @param message 收到的消息
     * @param session session
     */
    @OnMessage
    public void onMessage(String message, Session session) throws Exception {

        //获取模块id
        Message message1 = JSON.parseObject(message, Message.class);
        String token = String.valueOf(message1.getModulecode());
        CopyOnWriteArrayList<Session> sessions = SESSION_MAP.get(token);
        //将Modulecode作为key session作为值 一个Modulecode可能会有多个连接 所以session是一个集合
        if (sessions == null) {
            sessions = new CopyOnWriteArrayList<>();
        }
        //同个sessionId和modulecode生成一个唯一标识
        sessions.add(session);
        SESSION_MAP.put(token, sessions);
        AtomicInteger size = new AtomicInteger();
        SESSION_MAP.forEach((s, sessions1) -> size.set(sessions1.size() + size.get()));
        log.info("当前的session数量 : {}", size);
        //保存主机标识
        sessionCodeMap.remove(session.getId());
        sessionCodeMap.put(session.getId(), message1.getModulecode());
        //判断消息类型
        switch (message1.getMsgtype()) {
            //请求
            case "2":
                restHandle(message1);
                break;
            //回执
            case "3":
                receiptHandle(message1);
                break;
            default:
                throw new Exception("消息类型错误");
        }
    }

    /**
     * 处理回执消息
     *
     * @param message 消息
     */
    private void receiptHandle(Message message) {
        //

    }

    /**
     * 处理响应消息
     *
     * @param message 消息
     */
    private void restHandle(Message message) throws ParseException {

        Integer modulecode = message.getModulecode();
        //判断消息编码
        switch (message.getMsgcode()) {
            case 200001:
                break;
            //首次建立连接
            case 200000:
                //回执
                ReceiptHandleVo receiptHandleVo = new ReceiptHandleVo(message.getMsgid(), modulecode, new Date());
                send(String.valueOf(modulecode), JSON.toJSONString(receiptHandleVo));
                //拼装消息
                assemble(message);
            default:
        }
    }

    /**
     * 拼装并推送消息
     *
     * @param message 前端协议
     */
    private void assemble(Message message) throws ParseException {
        //判断是什么设备
        Optional.ofNullable(mqMessageService).orElseGet(() -> mqMessageService = ApplicationContextUtils.get(MqMessageService.class));
        MqMessage mqMessageSelect = new MqMessage();
        mqMessageSelect.setModulecode(message.getModulecode());
        MqMessage mqMessage = mqMessageService.selectOne(mqMessageSelect);
        if (mqMessage != null) {
            switch (mqMessage.getExchange()) {
                case "lcd":
                    //调用lcd设备的信息
                    lcd();
                    break;
                case "led":
                    //调用led设备信息
                    led();
                    break;
                case "light":
                    //调用照明设备信息
                    light();
                    break;
                case "platform":
                    //调用站台信息
                    platform();
                    break;
                //用电管理
                case "electricity":
                    //调用站台信息
                    electricity();
                    break;
                default:
            }
        }
    }

    /**
     * 拼装并发送用电管理信息
     */
    @Scheduled(cron = "${send.electricity-cron}")
    private void electricity() {
        //查出led的 moduleCode
        Integer modulecode = getModuleCode("electricity");
        String code = String.valueOf(modulecode);
        if (isOnClose(code)) {
            MessageVo messageVo = setMessageVo(modulecode);
            //所有站集中控制器的状态信息
            messageVo.setMsgcode(206001);
            //查出所有的公交站
            Optional.ofNullable(stationMapper).orElseGet(() -> stationMapper = ApplicationContextUtils.get(StationMapper.class));
            List<Station> stations = stationMapper.selectAll();
            //封装所有站集中控制器的状态信息
            List<Gateways> gateways = new ArrayList<>();
            stations.forEach(station -> {
                Gateways gateways1 = new Gateways(station);
                gateways.add(gateways1);
            });
            //推送所有站集中控制器的状态信息
            messageVo.setMsg(new GatewaysMsg(gateways));
            send(code, JSON.toJSONString(messageVo));
        }
    }

    /**
     * 拼装并发送站台管理信息
     */
    @Scheduled(cron = "${send.platform-cron}")
    private void platform() throws ParseException {
        //查出led的 moduleCode
        Integer modulecode = getModuleCode("platform");
        String code = String.valueOf(modulecode);
        if (isOnClose(code)) {
            MessageVo messageVo = setMessageVo(modulecode);
            //所有站设备的状态信息
            messageVo.setMsgcode(202002);
            List<DevicesMsg> lights = new ArrayList<>();
            //判断是什么设备
            Optional.ofNullable(deviceStationService).orElseGet(() -> deviceStationService = ApplicationContextUtils.get(DeviceStationService.class));
            Optional.ofNullable(stationService).orElseGet(() -> stationService = ApplicationContextUtils.get(StationService.class));
            //查询所有的公交站
            Optional.ofNullable(stationMapper).orElseGet(() -> stationMapper = ApplicationContextUtils.get(StationMapper.class));
            List<Station> stations = stationMapper.selectAll();

            DeviceStation deviceStationSelcet = new DeviceStation();
            stations.forEach(station -> {

                Integer stationid = station.getId();
                //查出该公交站下所有的设备
                deviceStationSelcet.setStationid(stationid);
                DevicesMsg devicesMsg = new DevicesMsg();
                devicesMsg.setStationid(stationid);
                devicesMsg.setStationname(station.getStationName());
                List<Devices> devices = new ArrayList<>();

                //添加lcd设备
                try {
                    setLcdDevices(devicesMsg, devices, stationid);
                } catch (Exception e) {
                    log.error("站台管理 获取lcd设备信息失败 : {}", e.getMessage());
                }
                //添加led设备
                try {
                    setLedDevices(devicesMsg, devices, stationid);
                } catch (Exception e) {
                    log.error("站台管理 获取led设备信息失败 : {}", e.getMessage());
                }
                //添加照明设备
                try {
                    setLightDevices(devicesMsg, devices, stationid);
                } catch (Exception e) {
                    log.error("站台管理 获取照明设备信息失败 : {}", e.getMessage());
                }
                lights.add(devicesMsg);
            });
            messageVo.setMsg(lights);
            send(code, JSON.toJSONString(messageVo));
            //拼装并发送站台统计信息
            messageVo.setMsgcode(202001);
            messageVo.setMsg(platformStatis());
            send(code, JSON.toJSONString(messageVo));
            //拼装并发送单个站台的信息
            messageVo.setMsgcode(202003);
            messageVo.setMsg(getSums(lights));
            send(code, JSON.toJSONString(messageVo));
            //发送单站离线设备信息
            messageVo.setMsgcode(202005);
            messageVo.setMsg(new StationsMsg(lights));
            send(code, JSON.toJSONString(messageVo));

            log.info("站台定时任务时间 : {}", messageVo.getTimestamp());
        }
    }


    /**
     * 计算站台设备能耗
     *
     * @param lights 站台设备信息
     * @return 计算结果
     */
    private SumsMsg getSums(List<DevicesMsg> lights) {

        Optional.ofNullable(deviceLoopMapper).orElseGet(() -> deviceLoopMapper = ApplicationContextUtils.get(DeviceLoopMapper.class));
        Optional.ofNullable(stationMapper).orElseGet(() -> stationMapper = ApplicationContextUtils.get(StationMapper.class));

        List<SumsVo> sumsVos = new ArrayList<>();
        List<SumsVo> sumsVoList = new ArrayList<>();

        lights.forEach(devicesMsg -> {
            SumsVo sumsVo = new SumsVo();
            sumsVo.setStationid(devicesMsg.getStationid());
            sumsVo.setStationname(devicesMsg.getStationname());
            //查出该站下所有的设备
            List<DeviceLoop> deviceLoopList = deviceLoopMapper.findByStationId(devicesMsg.getStationid());
            if (deviceLoopList != null && deviceLoopList.size() != 0) {
                //计算能耗
                try {
                    stationConsumption(sumsVo, deviceLoopList);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            sumsVos.add(sumsVo);
        });
        //遍历所有公交站 将公交站名称相同的 加起来
        List<Station> stations = stationMapper.selectAll();
        stations.forEach(station -> {
            SumsVo sumsVo = new SumsVo();
            sumsVo.setStationname(station.getStationName());
            sumsVo.setStationid(station.getId());
            sumsVos.forEach(sumsVo1 -> {
                if (station.getId().equals(sumsVo1.getStationid())) {
                    sumsVo.setData(sumsVo1);
                }
            });
            sumsVoList.add(sumsVo);
        });

        return new SumsMsg(sumsVoList);
    }

    /**
     * 计算公交站的能耗
     *
     * @param sumsVo         要封装的能耗信息
     * @param deviceLoopList deviceLoopList 公交站下的设备信息
     */
    private void stationConsumption(SumsVo sumsVo, List<DeviceLoop> deviceLoopList) throws ParseException {
        //本月能耗
        float currentmonth = 0;
        //上月能耗
        float lastmonth = 0;
        //本年能耗
        float thisyear = 0;
        //灯箱能耗
        float lamphouse = 0;
        //顶棚能耗
        float platfond = 0;
        //logo能耗
        float logo = 0;
        //led能耗
        float led = 0;
        //lcd能耗
        float lcd = 0;

        StatisticsVo statisticsVo = new StatisticsVo();
        for (DeviceLoop deviceLoop : deviceLoopList) {
            //计算能耗
            statisticsVo.setDid(deviceLoop.getGatewayDid());
            statisticsVo.setLoop(deviceLoop.getLoop());

            StatisticsMsgVo statistics = Statistics.findStatistics(statisticsVo);
            currentmonth = currentmonth + statistics.getCurrentmonth();
            lastmonth = lastmonth + statistics.getLastmonth();
            thisyear = thisyear + statistics.getThisyear();

            switch (deviceLoop.getTypecode()) {
                //顶棚照明
                case 1:
                    platfond = platfond + statistics.getActivepowerNow();
                    break;
                //灯箱照明
                case 2:
                    lamphouse = lamphouse + statistics.getActivepowerNow();
                    break;
                //logo
                case 3:
                    logo = logo + statistics.getActivepowerNow();
                    break;
                //led
                case 4:
                    led = led + statistics.getActivepowerNow();
                    break;
                //lcd
                case 5:
                    lcd = lcd + statistics.getActivepowerNow();
                    break;

                default:
            }

        }
        sumsVo.setCurrentmonth(currentmonth);
        sumsVo.setLastmonth(lastmonth);
        sumsVo.setThisyear(thisyear);
        sumsVo.setLamphouse(lamphouse);
        sumsVo.setPlatfond(platfond);
        sumsVo.setLogo(logo);
        sumsVo.setLed(led);
        sumsVo.setLed(led);
    }

    /**
     * 拼装站台统计信息
     *
     * @return 拼装好的消息
     */
    @SuppressWarnings("Duplicates")
    private PlatformStatisVo platformStatis() throws ParseException {

        PlatformStatisVo platformStatisVo = new PlatformStatisVo();
        Optional.ofNullable(deviceLoopService).orElseGet(() -> deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class));
        DeviceLoop deviceLoopSelect = new DeviceLoop();
        StatisticsVo statisticsVo = new StatisticsVo();
        //本月
        float currentmonth = 0;
        //上月
        float lastmonth = 0;
        //本年
        float thisyear = 0;

        //lcd设备
        if (allStatusByRedis != null) {
            platformStatisVo.setLcdtotal(allStatusByRedis.size());
            //获取开启设备和关闭设备的总数
            platformStatisVo.setLcdonline((int) allStatusByRedis.stream().filter(iotLcdStatusTwo -> "1".equals(iotLcdStatusTwo.getStatus())).count());
            platformStatisVo.setLcdoffline((int) allStatusByRedis.stream().filter(iotLcdStatusTwo -> "0".equals(iotLcdStatusTwo.getStatus())).count());
            //能耗信息
            //查出设备的回路号
            for (IotLcdStatusTwo iotLcdStatusTwo : allStatusByRedis) {
                deviceLoopSelect.setDeviceDid(iotLcdStatusTwo.getId());
                //获得lcd设备的统计信息
                deviceLoopSelect.setDeviceDid(iotLcdStatusTwo.getId());
                deviceLoopSelect.setTypecode(5);
                DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
                if (deviceLoop != null) {
                    statisticsVo.setLoop(deviceLoop.getLoop());
                    statisticsVo.setDid(String.valueOf(deviceLoop.getGatewayDid()));
                }
                StatisticsMsgVo statistics = Statistics.findStatistics(statisticsVo);
                currentmonth = currentmonth + statistics.getCurrentmonth();
                lastmonth = lastmonth + statistics.getLastmonth();
                thisyear = thisyear + statistics.getThisyear();
            }
        }
        //获得led设备统计信息
        if (allStatus != null) {
            platformStatisVo.setLedtotal(allStatus.size());
            platformStatisVo.setLedtotal((int) allStatus.stream().filter(tStatusDto -> 1 == (tStatusDto.getState())).count());
            platformStatisVo.setLedtotal((int) allStatus.stream().filter(tStatusDto -> 0 == (tStatusDto.getState())).count());
            //能耗信息
            //查出设备的回路号
            for (TStatusDto status : allStatus) {
                deviceLoopSelect.setDeviceDid(status.getId());
                deviceLoopSelect.setTypecode(4);
                DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
                if (deviceLoop != null) {
                    statisticsVo.setLoop(deviceLoop.getLoop());
                    statisticsVo.setDid(String.valueOf(deviceLoop.getGatewayDid()));
                }
                StatisticsMsgVo statistics = Statistics.findStatistics(statisticsVo);
                currentmonth = currentmonth + statistics.getCurrentmonth();
                lastmonth = lastmonth + statistics.getLastmonth();
                thisyear = thisyear + statistics.getThisyear();
            }
        }
        //获得照明设备统计信息
        if (loopStatus != null) {
            platformStatisVo.setLighttotal(loopStatus.size());
            platformStatisVo.setLightonline((int) loopStatus.stream().filter(loopStateDto -> 1 == (loopStateDto.getState())).count());
            platformStatisVo.setLightoffline((int) loopStatus.stream().filter(loopStateDto -> 0 == (loopStateDto.getState())).count());
            //能耗信息
            //查出设备的回路号
            for (TLoopStateDto status : loopStatus) {
                statisticsVo.setLoop(status.getLoop());
                statisticsVo.setDid(String.valueOf(status.getGatewayId()));
                StatisticsMsgVo statistics = Statistics.findStatistics(statisticsVo);
                currentmonth = currentmonth + statistics.getCurrentmonth();
                lastmonth = lastmonth + statistics.getLastmonth();
                thisyear = thisyear + statistics.getThisyear();
            }
        }
        platformStatisVo.setCurrentmonth(currentmonth);
        platformStatisVo.setLastmonth(lastmonth);
        platformStatisVo.setThisyear(thisyear);
        return platformStatisVo;
    }


    /**
     * 封装照明设备信息
     *
     * @param devicesMsg 总设备信息
     * @param devices    照明设备信息
     * @param stationid  站台id
     */
    private void setLightDevices(DevicesMsg devicesMsg, List<Devices> devices, Integer stationid) {

        //判断照明设备是否为空
        Optional.ofNullable(loopStatus).orElseGet(() -> {
            Optional.ofNullable(loopStatusServiceApi).orElseGet(() -> loopStatusServiceApi = ApplicationContextUtils.get(LoopStatusServiceApi.class));
            loopStatus = loopStatusServiceApi.findLoopStatus();
            return loopStatus;
        });
        Optional.ofNullable(deviceLoopService).orElseGet(() -> deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class));
        Optional.ofNullable(deviceStationService).orElseGet(() -> deviceStationService = ApplicationContextUtils.get(DeviceStationService.class));
        //判断该设备是否在该网关下
        DeviceStation deviceStationSelect = new DeviceStation();
        loopStatus.forEach(loopStateDto -> {
            //通过回路id查出设备id
            DeviceLoop deviceLoopSelect = new DeviceLoop();
            deviceLoopSelect.setGatewayDid(loopStateDto.getGatewayId());
            deviceLoopSelect.setLoop(loopStateDto.getLoop());
            DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
            if (deviceLoop != null) {
                deviceStationSelect.setDeviceDid(deviceLoop.getDeviceDid());
                deviceStationSelect.setTypecode(deviceLoop.getTypecode());
                DeviceStation deviceStation = deviceStationService.selectOne(deviceStationSelect);
                if (deviceStation != null) {
                    if (stationid.equals(deviceStation.getStationid())) {
                        Devices device = new Devices(loopStateDto);
                        devices.add(device);
                    }
                }
            }
        });
        devicesMsg.setDevices(devices);
    }

    /**
     * 站台管理 添加led设备
     *
     * @param devicesMsg 要返回的设备信息
     * @param devices    所有设备信息
     * @param stationid  站台id
     */
    private void setLedDevices(DevicesMsg devicesMsg, List<Devices> devices, Integer stationid) {

        //判断led设备是否为空
        Optional.ofNullable(allStatus).orElseGet(() -> {
            Optional.ofNullable(tStatusService).orElseGet(() -> tStatusService = ApplicationContextUtils.get(TStatusService.class));
            allStatus = tStatusService.findAllStatusByRedis();
            return allStatus;
        });
        //判断该设备是否在该网关下
        isInGateway(devicesMsg, devices, stationid, "4");

    }


    /**
     * 站台管理添加lcd设备
     *
     * @param devicesMsg 要返回的设备信息
     * @param devices    所有设备信息
     * @param stationid  站台id
     */
    private void setLcdDevices(DevicesMsg devicesMsg, List<Devices> devices, Integer stationid) {

        Optional.ofNullable(deviceStationService).orElseGet(() -> deviceStationService = ApplicationContextUtils.get(DeviceStationService.class));
        Optional.ofNullable(iotLcdStatusService).orElseGet(() -> iotLcdStatusService = ApplicationContextUtils.get(IotLcdsStatusService.class));
        //判断lcd设备是否为空 如果为空就去查询
        Optional.ofNullable(allStatusByRedis).orElseGet(() -> {
            Optional.ofNullable(loopStatusServiceApi).orElseGet(() -> loopStatusServiceApi = ApplicationContextUtils.get(LoopStatusServiceApi.class));
            allStatusByRedis = iotLcdStatusService.findAllStatusByRedis();
            return allStatusByRedis;
        });
        //判断该设备是否在该站台下
        isInGateway(devicesMsg, devices, stationid, "5");
    }

    /**
     * 提取重复代码 判断该设备是否在该网关下
     *
     * @param devicesMsg 要封装的设备
     * @param devices    设备
     * @param stationid  公交站id
     * @param s 设备类型
     */
    @SuppressWarnings("Duplicates")
    private void isInGateway(DevicesMsg devicesMsg, List<Devices> devices, Integer stationid, String s) {
        DeviceStation deviceStationSelect = new DeviceStation();
        if (StringUtils.equals("4", s)) {
            allStatus.stream().filter(iotLedStatus -> {
                //查出设备信息
                deviceStationSelect.setTypecode(Integer.valueOf(s));
                deviceStationSelect.setDeviceDid(iotLedStatus.getId());
                DeviceStation deviceStation = deviceStationService.selectOne(deviceStationSelect);
                //判断是否在该公交站下
                if (deviceStation != null) {
                    return stationid.equals(deviceStation.getStationid());
                }
                return false;
            })
                    .forEach(iotLcdStatus -> {
                        Devices device = new Devices(iotLcdStatus);
                        devices.add(device);
                    });
        } else {
            allStatusByRedis.stream().filter(iotLedStatus -> {
                deviceStationSelect.setTypecode(Integer.valueOf(s));
                deviceStationSelect.setDeviceDid(iotLedStatus.getId());
                DeviceStation deviceStation = deviceStationService.selectOne(deviceStationSelect);
                if (deviceStation != null) {
                    return stationid.equals(deviceStation.getStationid());
                }
                return false;
            })
                    .forEach(iotLcdStatus -> {
                        Devices device = new Devices(iotLcdStatus);
                        devices.add(device);
                    });
        }
        devicesMsg.setDevices(devices);
    }


    /**
     * 照明首次连接 同时也定时推送
     */
    @Scheduled(cron = "${send.light-cron}")
    public void light() throws ParseException {
        Integer modulecode = getModuleCode("light");
        String code = String.valueOf(modulecode);
        if (isOnClose(code)) {
            MessageVo messageVo = setMessageVo(modulecode);
            //所有站照明的状态信息
            messageVo.setMsgcode(203001);
            //调用接口 获取当前照明状态
            Optional.ofNullable(loopStatusServiceApi).orElseGet(() -> loopStatusServiceApi = ApplicationContextUtils.get(LoopStatusServiceApi.class));
            loopStatus = loopStatusServiceApi.findLoopStatus();
            List<Lights> lightsList = new ArrayList<>();
            //保存设备状态信息
            LightMsgState lightMsgState = new LightMsgState();
            if (loopStatus != null && loopStatus.size() != 0) {
                //灯箱设备
                List<Lights> lamphouses = new ArrayList<>();
                //顶棚
                List<Lights> platfonds = new ArrayList<>();
                //log
                List<Lights> logos = new ArrayList<>();
                loopStatus.forEach(tLoopStateDto -> {
                    //判断这个回路下是什么设备
                    DeviceLoop deviceLoop = tLoopStateDtoIsNull(tLoopStateDto);
                    if (deviceLoop != null) {
                        Lights light = new Lights(deviceLoop, tLoopStateDto);
                        lightsList.add(light);
                        //判断这是什么设备
                        if (light.getLamphouseid() != null) {
                            lamphouses.add(light);
                        }
                        if (light.getPlatfondid() != null) {
                            platfonds.add(light);
                        }
                        if (light.getLogoid() != null) {
                            logos.add(light);
                        }
                    }
                });
                LightMsg lightMsg = new LightMsg(lightsList);
                messageVo.setMsg(lightMsg);
                send(code, JSON.toJSONString(messageVo));
                //推送设备状态信息
                lightMsgState.setLamphouses(lamphouses);
                lightMsgState.setPlatfonds(platfonds);
                lightMsgState.setLogos(logos);
                messageVo.setMsg(lightMsgState);
                messageVo.setMsgcode(203004);
                send(code, JSON.toJSONString(messageVo));

                //推送统计信息
                StatisticsMsgVo statisticsMsgVo = lightStatis(loopStatus);
                statisticsMsgVo.addLightNum(loopStatus);
                List<TEvent> event = loopStatusServiceApi.findEvent();
                LightMsgVo lightMsgVo = new LightMsgVo(statisticsMsgVo, event);
                messageVo.setMsg(lightMsgVo);
                messageVo.setMsgcode(203002);
                send(code, JSON.toJSONString(messageVo));

                //推送离线设备信息
                messageVo.setMsgcode(203005);
                OfflineMsg offlineMsg = new OfflineMsg();
                offlineMsg.offlineLightMsg(loopStatus);
                messageVo.setMsg(offlineMsg);
                send(code, JSON.toJSONString(messageVo));

                log.info("照明定时任务时间 : {}", messageVo.getTimestamp());
            }
        }
    }

    /**
     * 照明设备统计信息
     *
     * @param loopStatus 照明设备信息
     * @return 统计信息
     */
    private StatisticsMsgVo lightStatis(List<TLoopStateDto> loopStatus) throws ParseException {

        List<String> dids1 = new ArrayList<>();
        List<String> dids2 = new ArrayList<>();
        List<String> dids3 = new ArrayList<>();
        //取出所有的did
        loopStatus.forEach(loopStateDto -> {
            //通过回路号查询这个是什么设备
            DeviceLoopService deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class);
            DeviceLoop deviceLoopSelect = new DeviceLoop();
            deviceLoopSelect.setLoop(loopStateDto.getLoop());
            deviceLoopSelect.setGatewayDid(loopStateDto.getGatewayId());
            DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
            if (deviceLoop != null) {
                if (1 == deviceLoop.getTypecode()) {
                    dids1.add(String.valueOf(deviceLoop.getDeviceDid()));
                }
                if (2 == deviceLoop.getTypecode()) {
                    dids2.add(String.valueOf(deviceLoop.getDeviceDid()));
                }
                if (3 == deviceLoop.getTypecode()) {
                    dids3.add(String.valueOf(deviceLoop.getDeviceDid()));
                }
            }
        });
        //统计
        StatisticsMsgVo statisticsMsgVo1 = equipStatis(dids1, "1");
        StatisticsMsgVo statisticsMsgVo2 = equipStatis(dids2, "2");
        StatisticsMsgVo statisticsMsgVo3 = equipStatis(dids3, "3");

        return new StatisticsMsgVo(statisticsMsgVo1, statisticsMsgVo2, statisticsMsgVo3);
    }


    /**
     * lcd首次连接信息 也需要定时向前台推送
     */
    @Scheduled(cron = "${send.lcd-cron}")
    public void lcd() {
        //查出led的 moduleCode
        Integer modulecode = getModuleCode("lcd");
        String code = String.valueOf(modulecode);
        //判断该连接是要被关闭
        if (isOnClose(code)) {
            MessageVo messageVo = setMessageVo(modulecode);
            //调用接口 获得所有站屏的设备状态
            messageVo.setMsgcode(204001);
            Optional.ofNullable(tEventLcdService).orElseGet(() -> tEventLcdService = ApplicationContextUtils.get(TEventLcdService.class));
            Optional.ofNullable(iotLcdStatusService).orElseGet(() -> iotLcdStatusService = ApplicationContextUtils.get(IotLcdsStatusService.class));
            this.allStatusByRedis = iotLcdStatusService.findAllStatusByRedis();
            if (allStatusByRedis != null && allStatusByRedis.size() != 0) {
                LcdMsg lcdMsg = new LcdMsg(allStatusByRedis);
                messageVo.setMsg(lcdMsg);
                send(code, JSON.toJSONString(messageVo));

                //推送设备状态 将多余字段设置为 null
                allStatusByRedis.forEach(iotLcdStatus -> iotLcdStatus.setVolume(null));
                LcdMsg lcdMsg2 = new LcdMsg(allStatusByRedis);
                messageVo.setMsg(lcdMsg2);
                messageVo.setMsgcode(204004);
                send(code, JSON.toJSONString(messageVo));

                //推送统计信息
                LightMsgVo lightMsgVo = new LightMsgVo();
                lightMsgVo.lightMsgVoLcd(allStatusByRedis, tEventLcdService.findCountByTime());
                messageVo.setMsg(lightMsgVo);
                messageVo.setMsgcode(204002);
                send(code, JSON.toJSONString(messageVo));

                //推送离线设备信息
                messageVo.setMsgcode(204005);
                OfflineMsg offlineMsg = new OfflineMsg();
                offlineMsg.offlineLcdMsg(allStatusByRedis);
                messageVo.setMsg(offlineMsg);
                send(code, JSON.toJSONString(messageVo));

                log.info("lcd定时任务时间 : {}", messageVo.getTimestamp());
            }
        }
    }

    /**
     * 判断该连接是否被关闭
     *
     * @param code modulecode
     * @return 判断结果
     */
    private boolean isOnClose(String code) {
        CopyOnWriteArrayList<Session> sessions = SESSION_MAP.get(code);
        if (sessions != null) {
            for (Session session : sessions) {
                if (StringUtils.equals(code, String.valueOf(sessionCodeMap.get(session.getId())))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 重载判断该连接是否被关闭
     *
     * @param code modulecode
     * @return 判断结果
     */
    private synchronized boolean isOnClose(String code, String sessionId) {
        return StringUtils.equals(code, String.valueOf(sessionCodeMap.get(sessionId)));
    }

    /**
     * lcd设备统计信息
     *
     * @param allStatusByRedis 设备did
     * @return 能耗信息
     */
    private StatisticsMsgVo lcdStatis(List<IotLcdStatusTwo> allStatusByRedis) throws ParseException {

        List<String> dids = new ArrayList<>();
        //取出所有的did
        allStatusByRedis.forEach(iotLcdStatus -> dids.add(iotLcdStatus.getId()));
        //统计
        return equipStatis(dids, "5");
    }

    /**
     * led首次连接信息 也需要定时向前台推送
     */
    @Scheduled(cron = "${send.led-cron}")
    public void led() {
        //查出led的 moduleCode
        Integer modulecode = getModuleCode("led");
        String code = String.valueOf(modulecode);
        if (isOnClose(code)) {
            MessageVo messageVo = setMessageVo(modulecode);
            //所有站屏的设备状态
            messageVo.setMsgcode(205001);
            //调用接口
            Optional.ofNullable(tEventLedService).orElseGet(() -> tEventLedService = ApplicationContextUtils.get(TEventLedService.class));
            Optional.ofNullable(tStatusService).orElseGet(() -> tStatusService = ApplicationContextUtils.get(TStatusService.class));
            allStatus = tStatusService.findAllStatusByRedis();
            if (allStatus != null && allStatus.size() != 0) {
                Leds leds = new Leds(allStatus);
                messageVo.setMsg(leds);
                send(code, JSON.toJSONString(messageVo));

                allStatus.forEach(tStatusDto -> {
                    tStatusDto.setVolume(null);
                    tStatusDto.setLight(null);
                });
                //设备状态
                Leds leds2 = new Leds(allStatus);
                messageVo.setMsg(leds2);
                messageVo.setMsgcode(205004);
                send(code, JSON.toJSONString(messageVo));

                //推送统计信息
                LightMsgVo lightMsgVo = new LightMsgVo();
                lightMsgVo.lightMsgVoLed(allStatus, tEventLedService.findCountByTime());
                messageVo.setMsg(lightMsgVo);
                messageVo.setMsgcode(205002);
                send(code, JSON.toJSONString(messageVo));

                //推送离线设备信息
                messageVo.setMsgcode(205005);
                OfflineMsg offlineMsg = new OfflineMsg();
                offlineMsg.offlineLedMsg(allStatus);
                messageVo.setMsg(offlineMsg);
                send(code, JSON.toJSONString(messageVo));

                log.info("led定时任务时间 : {}", messageVo.getTimestamp());
            }
        }
    }

    /**
     * led设备统计信息
     *
     * @param allStatus led信息
     * @return 统计信息
     */
    private StatisticsMsgVo ledStatis(List<TStatusDto> allStatus) throws ParseException {

        List<String> dids = new ArrayList<>();
        //取出所有的did
        allStatus.forEach(tStatusDto -> dids.add(tStatusDto.getDid()));
        //统计
        StatisticsMsgVo statisticsMsgVo = equipStatis(dids, "4");
        statisticsMsgVo.addNum(allStatus);
        return statisticsMsgVo;

    }


    /**
     * 关闭连接的方法
     *
     * @param session session
     */
    @OnClose
    public synchronized static void onClose(Session session) {
        log.info("有连接关闭 sessionId : {}", session.getId());
        Map<String, CopyOnWriteArrayList<Session>> map = new HashMap<>(16);
        if (SESSION_MAP != null && SESSION_MAP.size() != 0) {
            SESSION_MAP.forEach((k, v) -> {
                //这里使用迭代器防止角标越界
                if (v != null && v.size() != 0) {
                    v.removeIf(next -> StringUtils.equals(next.getId(), session.getId()));
                    map.put(k, v);
                }
            });
            try {
                session.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            SESSION_MAP = map;
            //删除缓存
            Optional.ofNullable(redisTemplate).orElseGet(() -> redisTemplate = ApplicationContextUtils.get(StringRedisTemplate.class));
            sessionCodeMap.remove(session.getId());
            log.info("断开连接后session数量 : {}", SESSION_MAP.size());
        }
    }

    /**
     * 发送消息的方法
     *
     * @param token   modulecode
     * @param message 要发送的信息
     */
    public void send(String token, String message) {

        if (StringUtils.isNotBlank(token)) {
            //遍历session推送消息到页面
            Optional.ofNullable(SESSION_MAP.get(token)).ifPresent(session -> {
                if (session.size() != 0) {
                    session.forEach(session1 -> {
                        try {
                            if (isOnClose(token, session1.getId())) {
                                session1.getBasicRemote().sendText(message);
                                count = 0;
                            }
                        } catch (Exception e) {
                            //重新推送
                            if (retry != count) {
                                send(token, message);
                                log.info("消息推送失败,重试第 {} 次", count + 1);
                                count++;
                            } else {
                                count = 0;
                                onClose(session1);
                                log.error("消息推送失败 : {}", e.getMessage());
                            }
                        }
                    });
                }
            });
            //删除缓存
            Optional.ofNullable(redisTemplate).orElseGet(() -> redisTemplate = ApplicationContextUtils.get(StringRedisTemplate.class));
            try {
                redisTemplate.opsForHash().delete("web_socket_key", JSON.parseObject(message, Message.class).getMsgid());
            } catch (Exception e) {
                log.error("删除缓存错误 : {}", e.getMessage());
            }
        }
    }

    /**
     * 发生错误调用的方法
     *
     * @param session session
     * @param error   错误信息
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("发生错误 sessionId : {} ", session.getId());
        //发生错误后 该session会被关闭 要被删除
        //     onClose(session);
        error.printStackTrace();
    }

    /**
     * 将时间格式化为 yyyy-MM-dd HH:mm:ss.SSS
     *
     * @param date 要格式化的时间
     * @return 格式化的结果
     */
    private String dateFormat(Date date) {

        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(date);
    }

    /**
     * 提取重复代码
     *
     * @param modulecode modulecode
     * @return MessageVo
     */
    private MessageVo setMessageVo(Integer modulecode) {
        //拼装消息
        MessageVo messageVo = new MessageVo();
        messageVo.setMsgid(UUID.randomUUID().toString());
        messageVo.setModulecode(modulecode);
        messageVo.setMsgtype(1);
        messageVo.setTimestamp(dateFormat(new Date()));

        return messageVo;
    }

    /**
     * 提取重复代码 获取Modulecode
     *
     * @return Modulecode
     */
    private Integer getModuleCode(String equipment) {
        Optional.ofNullable(mqMessageService).orElseGet(() -> mqMessageService = ApplicationContextUtils.get(MqMessageService.class));
        MqMessage mqMessageSelect = new MqMessage();
        mqMessageSelect.setExchange(equipment);
        return mqMessageService.selectOne(mqMessageSelect).getModulecode();
    }

    /**
     * 提取重复代码
     *
     * @param dids     设备did
     * @param typeCode 设备类型
     * @return 统计信息
     * @throws ParseException 时间格式化异常
     */
    private StatisticsMsgVo equipStatis(List<String> dids, String typeCode) throws ParseException {

        Optional.ofNullable(deviceLoopService).orElseGet(() -> deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class));
        //遍历通过did查出回路
        DeviceLoop deviceLoopSelect = new DeviceLoop();
        //本月
        float currentmonth = 0;
        //上月
        float lastmonth = 0;
        //本年
        float thisyear = 0;
        //当前
        float activepowerNow = 0;
        for (String did : dids) {
            if (StringUtils.isNotBlank(did)) {
                deviceLoopSelect.setDeviceDid(did);
                deviceLoopSelect.setTypecode(Integer.valueOf(typeCode));
                DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
                //查出单个设备的统计信息
                if (deviceLoop != null) {
                    StatisticsVo statisticsVoSelect = new StatisticsVo();
                    statisticsVoSelect.setDid(String.valueOf(deviceLoop.getGatewayDid()));
                    statisticsVoSelect.setLoop(deviceLoop.getLoop());
                    StatisticsMsgVo statistics = Statistics.findStatistics(statisticsVoSelect);
                    currentmonth = currentmonth + statistics.getCurrentmonth();
                    lastmonth = lastmonth + statistics.getLastmonth();
                    thisyear = thisyear + statistics.getThisyear();
                    activepowerNow = activepowerNow + statistics.getActivepowerNow();
                }
            }
        }
        return new StatisticsMsgVo(currentmonth, lastmonth, thisyear, activepowerNow);
    }


    private DeviceLoop tLoopStateDtoIsNull(TLoopStateDto tLoopStateDto) {
        //通过回路号查询这个是什么设备
        Optional.ofNullable(deviceLoopService).orElseGet(() -> deviceLoopService = ApplicationContextUtils.get(DeviceLoopService.class));
        DeviceLoop deviceLoopSelect = new DeviceLoop();
        deviceLoopSelect.setLoop(tLoopStateDto.getLoop());
        deviceLoopSelect.setGatewayDid(tLoopStateDto.getGatewayId());
        DeviceLoop deviceLoop = deviceLoopService.selectOne(deviceLoopSelect);
        if (deviceLoop != null) {
            if (deviceLoop.getTypecode() == 1 || deviceLoop.getTypecode() == 2 || deviceLoop.getTypecode() == 3) {
                return deviceLoop;
            }
        }
        return null;
    }

    /**
     * 安公交站整个设备
     *
     * @param lights 设备
     * @return 拼装
     */
    private List<DevicesMsg> lightsStation(List<DevicesMsg> lights) {

        Optional.ofNullable(stationMapper).orElseGet(() -> stationMapper = ApplicationContextUtils.get(StationMapper.class));
        List<DevicesMsg> lightList = new ArrayList<>();
        //遍历所有公交站 将公交站名称相同的 加起来
        List<Station> stations = stationMapper.selectAll();
        stations.forEach(station -> {
            DevicesMsg devicesMsg = new DevicesMsg();
            devicesMsg.setStationname(station.getStationName());
            devicesMsg.setStationid(station.getId());
            lights.forEach(devicesMsg1 -> {
                if (station.getId().equals(devicesMsg1.getStationid())) {
                    devicesMsg.setData(devicesMsg1);
                }
            });
            lightList.add(devicesMsg);
        });
        return lightList;

    }
}
