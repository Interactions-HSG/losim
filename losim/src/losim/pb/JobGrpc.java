package losim.pb;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class JobGrpc {

  private JobGrpc() {}

  public static final java.lang.String SERVICE_NAME = "losim.Job";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<losim.pb.Input,
      losim.pb.Workload> getLoadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Load",
      requestType = losim.pb.Input.class,
      responseType = losim.pb.Workload.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<losim.pb.Input,
      losim.pb.Workload> getLoadMethod() {
    io.grpc.MethodDescriptor<losim.pb.Input, losim.pb.Workload> getLoadMethod;
    if ((getLoadMethod = JobGrpc.getLoadMethod) == null) {
      synchronized (JobGrpc.class) {
        if ((getLoadMethod = JobGrpc.getLoadMethod) == null) {
          JobGrpc.getLoadMethod = getLoadMethod =
              io.grpc.MethodDescriptor.<losim.pb.Input, losim.pb.Workload>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Load"))
              .setSafe(true)
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  losim.pb.Input.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  losim.pb.Workload.getDefaultInstance()))
              .setSchemaDescriptor(new JobMethodDescriptorSupplier("Load"))
              .build();
        }
      }
    }
    return getLoadMethod;
  }

  private static volatile io.grpc.MethodDescriptor<losim.pb.Workload,
      losim.pb.Result> getRunMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Run",
      requestType = losim.pb.Workload.class,
      responseType = losim.pb.Result.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<losim.pb.Workload,
      losim.pb.Result> getRunMethod() {
    io.grpc.MethodDescriptor<losim.pb.Workload, losim.pb.Result> getRunMethod;
    if ((getRunMethod = JobGrpc.getRunMethod) == null) {
      synchronized (JobGrpc.class) {
        if ((getRunMethod = JobGrpc.getRunMethod) == null) {
          JobGrpc.getRunMethod = getRunMethod =
              io.grpc.MethodDescriptor.<losim.pb.Workload, losim.pb.Result>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Run"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  losim.pb.Workload.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  losim.pb.Result.getDefaultInstance()))
              .setSchemaDescriptor(new JobMethodDescriptorSupplier("Run"))
              .build();
        }
      }
    }
    return getRunMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static JobStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JobStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JobStub>() {
        @java.lang.Override
        public JobStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JobStub(channel, callOptions);
        }
      };
    return JobStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static JobBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JobBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JobBlockingV2Stub>() {
        @java.lang.Override
        public JobBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JobBlockingV2Stub(channel, callOptions);
        }
      };
    return JobBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static JobBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JobBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JobBlockingStub>() {
        @java.lang.Override
        public JobBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JobBlockingStub(channel, callOptions);
        }
      };
    return JobBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static JobFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JobFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JobFutureStub>() {
        @java.lang.Override
        public JobFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JobFutureStub(channel, callOptions);
        }
      };
    return JobFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Off the clock, before anything is measured.
     * </pre>
     */
    default void load(losim.pb.Input request,
        io.grpc.stub.StreamObserver<losim.pb.Workload> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLoadMethod(), responseObserver);
    }

    /**
     * <pre>
     * The simulation. It ends when this returns.
     * </pre>
     */
    default void run(losim.pb.Workload request,
        io.grpc.stub.StreamObserver<losim.pb.Result> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRunMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Job.
   */
  public static abstract class JobImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return JobGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Job.
   */
  public static final class JobStub
      extends io.grpc.stub.AbstractAsyncStub<JobStub> {
    private JobStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JobStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JobStub(channel, callOptions);
    }

    /**
     * <pre>
     * Off the clock, before anything is measured.
     * </pre>
     */
    public void load(losim.pb.Input request,
        io.grpc.stub.StreamObserver<losim.pb.Workload> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLoadMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * The simulation. It ends when this returns.
     * </pre>
     */
    public void run(losim.pb.Workload request,
        io.grpc.stub.StreamObserver<losim.pb.Result> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRunMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Job.
   */
  public static final class JobBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<JobBlockingV2Stub> {
    private JobBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JobBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JobBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Off the clock, before anything is measured.
     * </pre>
     */
    public losim.pb.Workload load(losim.pb.Input request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getLoadMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * The simulation. It ends when this returns.
     * </pre>
     */
    public losim.pb.Result run(losim.pb.Workload request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRunMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service Job.
   */
  public static final class JobBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<JobBlockingStub> {
    private JobBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JobBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JobBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Off the clock, before anything is measured.
     * </pre>
     */
    public losim.pb.Workload load(losim.pb.Input request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLoadMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * The simulation. It ends when this returns.
     * </pre>
     */
    public losim.pb.Result run(losim.pb.Workload request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRunMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Job.
   */
  public static final class JobFutureStub
      extends io.grpc.stub.AbstractFutureStub<JobFutureStub> {
    private JobFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JobFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JobFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Off the clock, before anything is measured.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<losim.pb.Workload> load(
        losim.pb.Input request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLoadMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * The simulation. It ends when this returns.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<losim.pb.Result> run(
        losim.pb.Workload request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRunMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LOAD = 0;
  private static final int METHODID_RUN = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_LOAD:
          serviceImpl.load((losim.pb.Input) request,
              (io.grpc.stub.StreamObserver<losim.pb.Workload>) responseObserver);
          break;
        case METHODID_RUN:
          serviceImpl.run((losim.pb.Workload) request,
              (io.grpc.stub.StreamObserver<losim.pb.Result>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getLoadMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              losim.pb.Input,
              losim.pb.Workload>(
                service, METHODID_LOAD)))
        .addMethod(
          getRunMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              losim.pb.Workload,
              losim.pb.Result>(
                service, METHODID_RUN)))
        .build();
  }

  private static abstract class JobBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    JobBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return losim.pb.JobOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Job");
    }
  }

  private static final class JobFileDescriptorSupplier
      extends JobBaseDescriptorSupplier {
    JobFileDescriptorSupplier() {}
  }

  private static final class JobMethodDescriptorSupplier
      extends JobBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    JobMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (JobGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new JobFileDescriptorSupplier())
              .addMethod(getLoadMethod())
              .addMethod(getRunMethod())
              .build();
        }
      }
    }
    return result;
  }
}
