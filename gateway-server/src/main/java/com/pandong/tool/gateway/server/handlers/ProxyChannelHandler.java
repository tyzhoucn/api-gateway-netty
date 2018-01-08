package com.pandong.tool.gateway.server.handlers;

import com.pandong.common.generater.IdGenerate;
import com.pandong.tool.gateway.common.GatewayCounter;
import com.pandong.tool.gateway.common.Global;
import com.pandong.tool.gateway.common.Node;
import com.pandong.tool.gateway.common.Service;
import com.pandong.tool.gateway.server.utils.ServerUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * @author pandong
 */

@Slf4j
public class ProxyChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
    ctx.channel().attr(ServerUtil.ChannelAttribute.REQUEST_TIME_START).set(System.currentTimeMillis());
    InetSocketAddress inetSocketAddress = (InetSocketAddress) ctx.channel().localAddress();
    Service service = ServerUtil.getService(inetSocketAddress.getPort());
    GatewayCounter.urlCount(service, msg.uri());

    List<Node> nodeList = Global.GATEWAY_CACHE.list(ServerUtil.CacheName.CACHE_NAME_SERVICE_NODE_LIST + service.getName());
    long requestTimes = GatewayCounter.getServiceRequestCounter(service);
    Node node = ServerUtil.loadBalancer(requestTimes, nodeList);
    HttpRequest request = hander(msg, node, inetSocketAddress, requestTimes);
    String connection = msg.headers().get(HttpHeaderNames.CONNECTION);
    if (HttpHeaderValues.KEEP_ALIVE.contentEquals(connection) && ServerUtil.containsKeepAliveChannel(service, node)) {
      Channel requestChannel = ServerUtil.getKeepAliveChannel(service, node);
      if(requestChannel.isWritable()){
        bindChannel(ctx.channel(), requestChannel, service, node);
        sendRequest(requestChannel, msg, request);
      }else{
        newConnect(service,node, ctx,msg,request);
      }
    }else{
      newConnect(service,node, ctx,msg,request);
    }
  }

  private void newConnect(Service service, Node node, ChannelHandlerContext ctx, FullHttpRequest msg, HttpRequest request){
    Bootstrap bootstrap = ServerUtil.getBootstrap(service);
    bootstrap.connect(node.getHost(), node.getPort()).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
          log.info("connect node[" + node.getHost() + ":" + node.getPort() + "] success");
          bindChannel(ctx.channel(), future.channel(), service, node);
          sendRequest(future.channel(), msg, request);
        } else {
          log.info("connect node[" + node.getHost() + ":" + node.getPort() + "] fail");
          ctx.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                  .addListener(ChannelFutureListener.CLOSE);
        }
      }
    });
  }

  private FullHttpRequest hander(FullHttpRequest msg, Node node, InetSocketAddress inetSocketAddress, long requestTimes) {
    FullHttpRequest request = msg.copy();

    String host = request.headers().get(HttpHeaderNames.HOST);
    long requestId = IdGenerate.generate(requestTimes);
    request.headers().set(HttpHeaderNames.HOST, node.getHost());
    request.headers().add("X-Request_Id", String.valueOf(requestId));
    request.headers().add("X-Proxy-Host", inetSocketAddress.getHostString());
    request.headers().add("X-Original-Host", host);
    return request;
  }

  private void sendRequest(Channel channel, FullHttpRequest msg, HttpRequest request) {
    channel.writeAndFlush(request);
    channel.config().setOption(ChannelOption.AUTO_READ, true);
  }

  //bind channel
  private void bindChannel(Channel proxyChannel, Channel requestChannel, Service service, Node node) {
    requestChannel.attr(ServerUtil.ChannelAttribute.PROXY_CHANNEL).set(proxyChannel);
    requestChannel.attr(ServerUtil.ChannelAttribute.SERVICE).set(service);
    requestChannel.attr(ServerUtil.ChannelAttribute.NODE).set(node);
    proxyChannel.attr(ServerUtil.ChannelAttribute.REQUEST_CHANNEL).set(requestChannel);
  }
}
