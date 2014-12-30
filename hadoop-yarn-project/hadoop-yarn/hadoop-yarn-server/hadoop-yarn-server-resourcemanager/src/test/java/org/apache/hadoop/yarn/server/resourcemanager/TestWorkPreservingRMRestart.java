/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.resourcemanager.TestRMRestart.TestSecurityMockRM;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.MemoryRMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.AbstractYarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueNotFoundException;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplication;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.LeafQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.ParentQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSParentQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairSchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies.DominantResourceFairnessPolicy;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fifo.FifoScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.security.DelegationTokenRenewer;
import org.apache.hadoop.yarn.util.ControlledClock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hadoop.yarn.util.resource.DominantResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.google.common.base.Supplier;


@SuppressWarnings({"rawtypes", "unchecked"})
@RunWith(value = Parameterized.class)
public class TestWorkPreservingRMRestart {

  private YarnConfiguration conf;
  private Class<?> schedulerClass;
  MockRM rm1 = null;
  MockRM rm2 = null;

  @Before
  public void setup() throws UnknownHostException {
    Logger rootLogger = LogManager.getRootLogger();
    rootLogger.setLevel(Level.DEBUG);
    conf = new YarnConfiguration();
    UserGroupInformation.setConfiguration(conf);
    conf.set(YarnConfiguration.RECOVERY_ENABLED, "true");
    conf.set(YarnConfiguration.RM_STORE, MemoryRMStateStore.class.getName());
    conf.setClass(YarnConfiguration.RM_SCHEDULER, schedulerClass,
      ResourceScheduler.class);
    conf.setBoolean(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_ENABLED, true);
    conf.setLong(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_SCHEDULING_WAIT_MS, 0);
    DefaultMetricsSystem.setMiniClusterMode(true);
  }

  @After
  public void tearDown() {
    if (rm1 != null) {
      rm1.stop();
    }
    if (rm2 != null) {
      rm2.stop();
    }
  }

  @Parameterized.Parameters
  public static Collection<Object[]> getTestParameters() {
    return Arrays.asList(new Object[][] { { CapacityScheduler.class },
        { FifoScheduler.class }, {FairScheduler.class } });
  }

  public TestWorkPreservingRMRestart(Class<?> schedulerClass) {
    this.schedulerClass = schedulerClass;
  }

  // Test common scheduler state including SchedulerAttempt, SchedulerNode,
  // AppSchedulingInfo can be reconstructed via the container recovery reports
  // on NM re-registration.
  // Also test scheduler specific changes: i.e. Queue recovery-
  // CSQueue/FSQueue/FifoQueue recovery respectively.
  // Test Strategy: send 3 container recovery reports(AMContainer, running
  // container, completed container) on NM re-registration, check the states of
  // SchedulerAttempt, SchedulerNode etc. are updated accordingly.
  @Test(timeout = 20000)
  public void testSchedulerRecovery() throws Exception {
    conf.setBoolean(CapacitySchedulerConfiguration.ENABLE_USER_METRICS, true);
    conf.set(CapacitySchedulerConfiguration.RESOURCE_CALCULATOR_CLASS,
      DominantResourceCalculator.class.getName());

    int containerMemory = 1024;
    Resource containerResource = Resource.newInstance(containerMemory, 1);

    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    if (schedulerClass.equals(FairScheduler.class)) {
      initFairScheduler(rm1);
    }
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    // clear queue metrics
    rm1.clearQueueMetrics(app1);

    // Re-start RM
    rm2 = new MockRM(conf, memStore);
    if (schedulerClass.equals(FairScheduler.class)) {
      initFairScheduler(rm2);
    }
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    // recover app
    RMApp recoveredApp1 =
        rm2.getRMContext().getRMApps().get(app1.getApplicationId());
    RMAppAttempt loadedAttempt1 = recoveredApp1.getCurrentAppAttempt();
    NMContainerStatus amContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 1,
          ContainerState.RUNNING);
    NMContainerStatus runningContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 2,
          ContainerState.RUNNING);
    NMContainerStatus completedContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 3,
          ContainerState.COMPLETE);

    nm1.registerNode(Arrays.asList(amContainer, runningContainer,
      completedContainer), null);

    // Wait for RM to settle down on recovering containers;
    waitForNumContainersToRecover(2, rm2, am1.getApplicationAttemptId());
    Set<ContainerId> launchedContainers =
        ((RMNodeImpl) rm2.getRMContext().getRMNodes().get(nm1.getNodeId()))
          .getLaunchedContainers();
    assertTrue(launchedContainers.contains(amContainer.getContainerId()));
    assertTrue(launchedContainers.contains(runningContainer.getContainerId()));

    // check RMContainers are re-recreated and the container state is correct.
    rm2.waitForState(nm1, amContainer.getContainerId(),
      RMContainerState.RUNNING);
    rm2.waitForState(nm1, runningContainer.getContainerId(),
      RMContainerState.RUNNING);
    rm2.waitForContainerToComplete(loadedAttempt1, completedContainer);

    AbstractYarnScheduler scheduler =
        (AbstractYarnScheduler) rm2.getResourceScheduler();
    SchedulerNode schedulerNode1 = scheduler.getSchedulerNode(nm1.getNodeId());
    assertTrue(
        "SchedulerNode#toString is not in expected format",
        schedulerNode1
        .toString().contains(schedulerNode1.getAvailableResource().toString()));
    assertTrue(
        "SchedulerNode#toString is not in expected format",
        schedulerNode1
        .toString().contains(schedulerNode1.getUsedResource().toString()));

    // ********* check scheduler node state.*******
    // 2 running containers.
    Resource usedResources = Resources.multiply(containerResource, 2);
    Resource nmResource =
        Resource.newInstance(nm1.getMemory(), nm1.getvCores());

    assertTrue(schedulerNode1.isValidContainer(amContainer.getContainerId()));
    assertTrue(schedulerNode1.isValidContainer(runningContainer
      .getContainerId()));
    assertFalse(schedulerNode1.isValidContainer(completedContainer
      .getContainerId()));
    // 2 launched containers, 1 completed container
    assertEquals(2, schedulerNode1.getNumContainers());

    assertEquals(Resources.subtract(nmResource, usedResources),
      schedulerNode1.getAvailableResource());
    assertEquals(usedResources, schedulerNode1.getUsedResource());
    Resource availableResources = Resources.subtract(nmResource, usedResources);

    // ***** check queue state based on the underlying scheduler ********
    Map<ApplicationId, SchedulerApplication> schedulerApps =
        ((AbstractYarnScheduler) rm2.getResourceScheduler())
          .getSchedulerApplications();
    SchedulerApplication schedulerApp =
        schedulerApps.get(recoveredApp1.getApplicationId());

    if (schedulerClass.equals(CapacityScheduler.class)) {
      checkCSQueue(rm2, schedulerApp, nmResource, nmResource, usedResources, 2);
    } else if (schedulerClass.equals(FifoScheduler.class)) {
      checkFifoQueue(rm2, schedulerApp, usedResources, availableResources);
    } else if (schedulerClass.equals(FairScheduler.class)) {
      checkFSQueue(rm2, schedulerApp, usedResources, availableResources);
    }

    // *********** check scheduler attempt state.********
    SchedulerApplicationAttempt schedulerAttempt =
        schedulerApp.getCurrentAppAttempt();
    assertTrue(schedulerAttempt.getLiveContainers().contains(
      scheduler.getRMContainer(amContainer.getContainerId())));
    assertTrue(schedulerAttempt.getLiveContainers().contains(
      scheduler.getRMContainer(runningContainer.getContainerId())));
    assertEquals(schedulerAttempt.getCurrentConsumption(), usedResources);

    // *********** check appSchedulingInfo state ***********
    assertEquals((1L << 40) + 1L, schedulerAttempt.getNewContainerId());
  }

  private void checkCSQueue(MockRM rm,
      SchedulerApplication<SchedulerApplicationAttempt> app,
      Resource clusterResource, Resource queueResource, Resource usedResource,
      int numContainers)
      throws Exception {
    checkCSLeafQueue(rm, app, clusterResource, queueResource, usedResource,
        numContainers);

    LeafQueue queue = (LeafQueue) app.getQueue();
    Resource availableResources =
        Resources.subtract(queueResource, usedResource);
    // ************ check app headroom ****************
    SchedulerApplicationAttempt schedulerAttempt = app.getCurrentAppAttempt();
    assertEquals(availableResources, schedulerAttempt.getHeadroom());

    // ************* check Queue metrics ************
    QueueMetrics queueMetrics = queue.getMetrics();
    assertMetrics(queueMetrics, 1, 0, 1, 0, 2, availableResources.getMemory(),
        availableResources.getVirtualCores(), usedResource.getMemory(),
        usedResource.getVirtualCores());

    // ************ check user metrics ***********
    QueueMetrics userMetrics =
        queueMetrics.getUserMetrics(app.getUser());
    assertMetrics(userMetrics, 1, 0, 1, 0, 2, availableResources.getMemory(),
        availableResources.getVirtualCores(), usedResource.getMemory(),
        usedResource.getVirtualCores());
  }

  private void checkCSLeafQueue(MockRM rm,
      SchedulerApplication<SchedulerApplicationAttempt> app,
      Resource clusterResource, Resource queueResource, Resource usedResource,
      int numContainers) {
    LeafQueue leafQueue = (LeafQueue) app.getQueue();
    // assert queue used resources.
    assertEquals(usedResource, leafQueue.getUsedResources());
    assertEquals(numContainers, leafQueue.getNumContainers());

    ResourceCalculator calc =
        ((CapacityScheduler) rm.getResourceScheduler()).getResourceCalculator();
    float usedCapacity =
        Resources.divide(calc, clusterResource, usedResource, queueResource);
    // assert queue used capacity
    assertEquals(usedCapacity, leafQueue.getUsedCapacity(), 1e-8);
    float absoluteUsedCapacity =
        Resources.divide(calc, clusterResource, usedResource, clusterResource);
    // assert queue absolute capacity
    assertEquals(absoluteUsedCapacity, leafQueue.getAbsoluteUsedCapacity(),
      1e-8);
    // assert user consumed resources.
    assertEquals(usedResource, leafQueue.getUser(app.getUser())
      .getTotalConsumedResources());
  }

  private void checkFifoQueue(ResourceManager rm,
      SchedulerApplication  schedulerApp, Resource usedResources,
      Resource availableResources) throws Exception {
    FifoScheduler scheduler = (FifoScheduler) rm.getResourceScheduler();
    // ************ check cluster used Resources ********
    assertEquals(usedResources, scheduler.getUsedResource());

    // ************ check app headroom ****************
    SchedulerApplicationAttempt schedulerAttempt =
        schedulerApp.getCurrentAppAttempt();
    assertEquals(availableResources, schedulerAttempt.getHeadroom());

    // ************ check queue metrics ****************
    QueueMetrics queueMetrics = scheduler.getRootQueueMetrics();
    assertMetrics(queueMetrics, 1, 0, 1, 0, 2, availableResources.getMemory(),
        availableResources.getVirtualCores(), usedResources.getMemory(),
        usedResources.getVirtualCores());
  }

  private void checkFSQueue(ResourceManager rm,
      SchedulerApplication  schedulerApp, Resource usedResources,
      Resource availableResources) throws Exception {
    // waiting for RM's scheduling apps
    int retry = 0;
    Resource assumedFairShare = Resource.newInstance(8192, 8);
    while (true) {
      Thread.sleep(100);
      if (assumedFairShare.equals(((FairScheduler)rm.getResourceScheduler())
          .getQueueManager().getRootQueue().getFairShare())) {
        break;
      }
      retry++;
      if (retry > 30) {
        Assert.fail("Apps are not scheduled within assumed timeout");
      }
    }

    FairScheduler scheduler = (FairScheduler) rm.getResourceScheduler();
    FSParentQueue root = scheduler.getQueueManager().getRootQueue();
    // ************ check cluster used Resources ********
    assertTrue(root.getPolicy() instanceof DominantResourceFairnessPolicy);
    assertEquals(usedResources,root.getResourceUsage());

    // ************ check app headroom ****************
    FSAppAttempt schedulerAttempt =
        (FSAppAttempt) schedulerApp.getCurrentAppAttempt();
    assertEquals(availableResources, schedulerAttempt.getHeadroom());

    // ************ check queue metrics ****************
    QueueMetrics queueMetrics = scheduler.getRootQueueMetrics();
    assertMetrics(queueMetrics, 1, 0, 1, 0, 2, availableResources.getMemory(),
        availableResources.getVirtualCores(), usedResources.getMemory(),
        usedResources.getVirtualCores());
  }

  private void initFairScheduler(ResourceManager rm) throws IOException {
    FairScheduler scheduler = (FairScheduler) rm.getResourceScheduler();
    String testDir =
        new File(
            System.getProperty("test.build.data", "/tmp")).getAbsolutePath();
    String allocFile = new File(testDir, "test-queues").getAbsolutePath();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, allocFile);

    PrintWriter out = new PrintWriter(new FileWriter(allocFile));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("<defaultQueueSchedulingPolicy>fair</defaultQueueSchedulingPolicy>");
    out.println("<queue name=\"root\">");
    out.println("  <schedulingPolicy>drf</schedulingPolicy>");
    out.println("  <weight>1.0</weight>");
    out.println("  <fairSharePreemptionTimeout>100</fairSharePreemptionTimeout>");
    out.println("  <minSharePreemptionTimeout>120</minSharePreemptionTimeout>");
    out.println("  <fairSharePreemptionThreshold>.5</fairSharePreemptionThreshold>");
    out.println("</queue>");
    out.println("</allocations>");
    out.close();
  }

  // create 3 container reports for AM
  public static List<NMContainerStatus>
      createNMContainerStatusForApp(MockAM am) {
    List<NMContainerStatus> list =
        new ArrayList<NMContainerStatus>();
    NMContainerStatus amContainer =
        TestRMRestart.createNMContainerStatus(am.getApplicationAttemptId(), 1,
          ContainerState.RUNNING);
    NMContainerStatus runningContainer =
        TestRMRestart.createNMContainerStatus(am.getApplicationAttemptId(), 2,
          ContainerState.RUNNING);
    NMContainerStatus completedContainer =
        TestRMRestart.createNMContainerStatus(am.getApplicationAttemptId(), 3,
          ContainerState.COMPLETE);
    list.add(amContainer);
    list.add(runningContainer);
    list.add(completedContainer);
    return list;
  }

  private static final String R = "Default";
  private static final String A = "QueueA";
  private static final String B = "QueueB";
  //don't ever create the below queue ;-)
  private static final String QUEUE_DOESNT_EXIST = "NoSuchQueue";
  private static final String USER_1 = "user1";
  private static final String USER_2 = "user2";

  private void setupQueueConfiguration(CapacitySchedulerConfiguration conf) {
    conf.setQueues(CapacitySchedulerConfiguration.ROOT, new String[] { R });
    final String Q_R = CapacitySchedulerConfiguration.ROOT + "." + R;
    conf.setCapacity(Q_R, 100);
    final String Q_A = Q_R + "." + A;
    final String Q_B = Q_R + "." + B;
    conf.setQueues(Q_R, new String[] {A, B});
    conf.setCapacity(Q_A, 50);
    conf.setCapacity(Q_B, 50);
    conf.setDouble(CapacitySchedulerConfiguration
      .MAXIMUM_APPLICATION_MASTERS_RESOURCE_PERCENT, 0.5f);
  }
  
  private void setupQueueConfigurationOnlyA(
      CapacitySchedulerConfiguration conf) {
    conf.setQueues(CapacitySchedulerConfiguration.ROOT, new String[] { R });
    final String Q_R = CapacitySchedulerConfiguration.ROOT + "." + R;
    conf.setCapacity(Q_R, 100);
    final String Q_A = Q_R + "." + A;
    conf.setQueues(Q_R, new String[] {A});
    conf.setCapacity(Q_A, 100);
    conf.setDouble(CapacitySchedulerConfiguration
      .MAXIMUM_APPLICATION_MASTERS_RESOURCE_PERCENT, 1.0f);
  }

  // Test CS recovery with multi-level queues and multi-users:
  // 1. setup 2 NMs each with 8GB memory;
  // 2. setup 2 level queues: Default -> (QueueA, QueueB)
  // 3. User1 submits 2 apps on QueueA
  // 4. User2 submits 1 app  on QueueB
  // 5. AM and each container has 1GB memory
  // 6. Restart RM.
  // 7. nm1 re-syncs back containers belong to user1
  // 8. nm2 re-syncs back containers belong to user2.
  // 9. Assert the parent queue and 2 leaf queues state and the metrics.
  // 10. Assert each user's consumption inside the queue.
  @Test (timeout = 30000)
  public void testCapacitySchedulerRecovery() throws Exception {
    if (!schedulerClass.equals(CapacityScheduler.class)) {
      return;
    }
    conf.setBoolean(CapacitySchedulerConfiguration.ENABLE_USER_METRICS, true);
    conf.set(CapacitySchedulerConfiguration.RESOURCE_CALCULATOR_CLASS,
      DominantResourceCalculator.class.getName());
    CapacitySchedulerConfiguration csConf =
        new CapacitySchedulerConfiguration(conf);
    setupQueueConfiguration(csConf);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(csConf);
    rm1 = new MockRM(csConf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    MockNM nm2 =
        new MockNM("127.1.1.1:4321", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    nm2.registerNode();
    RMApp app1_1 = rm1.submitApp(1024, "app1_1", USER_1, null, A);
    MockAM am1_1 = MockRM.launchAndRegisterAM(app1_1, rm1, nm1);
    RMApp app1_2 = rm1.submitApp(1024, "app1_2", USER_1, null, A);
    MockAM am1_2 = MockRM.launchAndRegisterAM(app1_2, rm1, nm2);

    RMApp app2 = rm1.submitApp(1024, "app2", USER_2, null, B);
    MockAM am2 = MockRM.launchAndRegisterAM(app2, rm1, nm2);

    // clear queue metrics
    rm1.clearQueueMetrics(app1_1);
    rm1.clearQueueMetrics(app1_2);
    rm1.clearQueueMetrics(app2);

    csConf.set("yarn.scheduler.capacity.root.Default.QueueB.state", "STOPPED");

    // Re-start RM
    rm2 = new MockRM(csConf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    nm2.setResourceTrackerService(rm2.getResourceTrackerService());

    List<NMContainerStatus> am1_1Containers =
        createNMContainerStatusForApp(am1_1);
    List<NMContainerStatus> am1_2Containers =
        createNMContainerStatusForApp(am1_2);
    am1_1Containers.addAll(am1_2Containers);
    nm1.registerNode(am1_1Containers, null);

    List<NMContainerStatus> am2Containers =
        createNMContainerStatusForApp(am2);
    nm2.registerNode(am2Containers, null);

    // Wait for RM to settle down on recovering containers;
    waitForNumContainersToRecover(2, rm2, am1_1.getApplicationAttemptId());
    waitForNumContainersToRecover(2, rm2, am1_2.getApplicationAttemptId());
    waitForNumContainersToRecover(2, rm2, am1_2.getApplicationAttemptId());

    // Calculate each queue's resource usage.
    Resource containerResource = Resource.newInstance(1024, 1);
    Resource nmResource =
        Resource.newInstance(nm1.getMemory(), nm1.getvCores());
    Resource clusterResource = Resources.multiply(nmResource, 2);
    Resource q1Resource = Resources.multiply(clusterResource, 0.5);
    Resource q2Resource = Resources.multiply(clusterResource, 0.5);
    Resource q1UsedResource = Resources.multiply(containerResource, 4);
    Resource q2UsedResource = Resources.multiply(containerResource, 2);
    Resource totalUsedResource = Resources.add(q1UsedResource, q2UsedResource);
    Resource q1availableResources =
        Resources.subtract(q1Resource, q1UsedResource);
    Resource q2availableResources =
        Resources.subtract(q2Resource, q2UsedResource);
    Resource totalAvailableResource =
        Resources.add(q1availableResources, q2availableResources);

    Map<ApplicationId, SchedulerApplication> schedulerApps =
        ((AbstractYarnScheduler) rm2.getResourceScheduler())
          .getSchedulerApplications();
    SchedulerApplication schedulerApp1_1 =
        schedulerApps.get(app1_1.getApplicationId());

    // assert queue A state.
    checkCSLeafQueue(rm2, schedulerApp1_1, clusterResource, q1Resource,
      q1UsedResource, 4);
    QueueMetrics queue1Metrics = schedulerApp1_1.getQueue().getMetrics();
    assertMetrics(queue1Metrics, 2, 0, 2, 0, 4,
        q1availableResources.getMemory(),
        q1availableResources.getVirtualCores(), q1UsedResource.getMemory(),
        q1UsedResource.getVirtualCores());

    // assert queue B state.
    SchedulerApplication schedulerApp2 =
        schedulerApps.get(app2.getApplicationId());
    checkCSLeafQueue(rm2, schedulerApp2, clusterResource, q2Resource,
      q2UsedResource, 2);
    QueueMetrics queue2Metrics = schedulerApp2.getQueue().getMetrics();
    assertMetrics(queue2Metrics, 1, 0, 1, 0, 2,
        q2availableResources.getMemory(),
        q2availableResources.getVirtualCores(), q2UsedResource.getMemory(),
        q2UsedResource.getVirtualCores());

    // assert parent queue state.
    LeafQueue leafQueue = (LeafQueue) schedulerApp2.getQueue();
    ParentQueue parentQueue = (ParentQueue) leafQueue.getParent();
    checkParentQueue(parentQueue, 6, totalUsedResource, (float) 6 / 16,
      (float) 6 / 16);
    assertMetrics(parentQueue.getMetrics(), 3, 0, 3, 0, 6,
        totalAvailableResource.getMemory(),
        totalAvailableResource.getVirtualCores(), totalUsedResource.getMemory(),
        totalUsedResource.getVirtualCores());
  }
  
  //Test that we receive a meaningful exit-causing exception if a queue
  //is removed during recovery
  //1. Add some apps to two queues, attempt to add an app to a non-existant
  //   queue to verify that the new logic is not in effect during normal app
  //   submission
  //2. Remove one of the queues, restart the RM
  //3. Verify that the expected exception was thrown
  @Test (timeout = 30000, expected = QueueNotFoundException.class)
  public void testCapacitySchedulerQueueRemovedRecovery() throws Exception {
    if (!schedulerClass.equals(CapacityScheduler.class)) {
      throw new QueueNotFoundException("Dummy");
    }
    conf.setBoolean(CapacitySchedulerConfiguration.ENABLE_USER_METRICS, true);
    conf.set(CapacitySchedulerConfiguration.RESOURCE_CALCULATOR_CLASS,
      DominantResourceCalculator.class.getName());
    CapacitySchedulerConfiguration csConf =
        new CapacitySchedulerConfiguration(conf);
    setupQueueConfiguration(csConf);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(csConf);
    rm1 = new MockRM(csConf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    MockNM nm2 =
        new MockNM("127.1.1.1:4321", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    nm2.registerNode();
    RMApp app1_1 = rm1.submitApp(1024, "app1_1", USER_1, null, A);
    MockAM am1_1 = MockRM.launchAndRegisterAM(app1_1, rm1, nm1);
    RMApp app1_2 = rm1.submitApp(1024, "app1_2", USER_1, null, A);
    MockAM am1_2 = MockRM.launchAndRegisterAM(app1_2, rm1, nm2);

    RMApp app2 = rm1.submitApp(1024, "app2", USER_2, null, B);
    MockAM am2 = MockRM.launchAndRegisterAM(app2, rm1, nm2);
    
    //Submit an app with a non existant queue to make sure it does not
    //cause a fatal failure in the non-recovery case
    RMApp appNA = rm1.submitApp(1024, "app1_2", USER_1, null,
       QUEUE_DOESNT_EXIST, false);

    // clear queue metrics
    rm1.clearQueueMetrics(app1_1);
    rm1.clearQueueMetrics(app1_2);
    rm1.clearQueueMetrics(app2);

    // Re-start RM
    csConf =
        new CapacitySchedulerConfiguration(conf);
    setupQueueConfigurationOnlyA(csConf);
    rm2 = new MockRM(csConf, memStore);
    rm2.start();
  }

  private void checkParentQueue(ParentQueue parentQueue, int numContainers,
      Resource usedResource, float UsedCapacity, float absoluteUsedCapacity) {
    assertEquals(numContainers, parentQueue.getNumContainers());
    assertEquals(usedResource, parentQueue.getUsedResources());
    assertEquals(UsedCapacity, parentQueue.getUsedCapacity(), 1e-8);
    assertEquals(absoluteUsedCapacity, parentQueue.getAbsoluteUsedCapacity(), 1e-8);
  }

  // Test RM shuts down, in the meanwhile, AM fails. Restarted RM scheduler
  // should not recover the containers that belong to the failed AM.
  @Test(timeout = 20000)
  public void testAMfailedBetweenRMRestart() throws Exception {
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    conf.setLong(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_SCHEDULING_WAIT_MS, 0);
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());

    NMContainerStatus amContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 1,
          ContainerState.COMPLETE);
    NMContainerStatus runningContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 2,
          ContainerState.RUNNING);
    NMContainerStatus completedContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 3,
          ContainerState.COMPLETE);
    nm1.registerNode(Arrays.asList(amContainer, runningContainer,
      completedContainer), null);
    rm2.waitForState(am1.getApplicationAttemptId(), RMAppAttemptState.FAILED);
    // Wait for RM to settle down on recovering containers;
    Thread.sleep(3000);

    AbstractYarnScheduler scheduler =
        (AbstractYarnScheduler) rm2.getResourceScheduler();
    // Previous AM failed, The failed AM should once again release the
    // just-recovered containers.
    assertNull(scheduler.getRMContainer(runningContainer.getContainerId()));
    assertNull(scheduler.getRMContainer(completedContainer.getContainerId()));

    rm2.waitForNewAMToLaunchAndRegister(app1.getApplicationId(), 2, nm1);

    MockNM nm2 =
        new MockNM("127.1.1.1:4321", 8192, rm2.getResourceTrackerService());
    NMContainerStatus previousAttemptContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 4,
          ContainerState.RUNNING);
    nm2.registerNode(Arrays.asList(previousAttemptContainer), null);
    // Wait for RM to settle down on recovering containers;
    Thread.sleep(3000);
    // check containers from previous failed attempt should not be recovered.
    assertNull(scheduler.getRMContainer(previousAttemptContainer.getContainerId()));
  }

  // Apps already completed before RM restart. Restarted RM scheduler should not
  // recover containers for completed apps.
  @Test(timeout = 20000)
  public void testContainersNotRecoveredForCompletedApps() throws Exception {
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);
    MockRM.finishAMAndVerifyAppState(app1, rm1, nm1, am1);

    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    NMContainerStatus runningContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 2,
          ContainerState.RUNNING);
    NMContainerStatus completedContainer =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 3,
          ContainerState.COMPLETE);
    nm1.registerNode(Arrays.asList(runningContainer, completedContainer), null);
    RMApp recoveredApp1 =
        rm2.getRMContext().getRMApps().get(app1.getApplicationId());
    assertEquals(RMAppState.FINISHED, recoveredApp1.getState());

    // Wait for RM to settle down on recovering containers;
    Thread.sleep(3000);

    AbstractYarnScheduler scheduler =
        (AbstractYarnScheduler) rm2.getResourceScheduler();

    // scheduler should not recover containers for finished apps.
    assertNull(scheduler.getRMContainer(runningContainer.getContainerId()));
    assertNull(scheduler.getRMContainer(completedContainer.getContainerId()));
  }

  @Test (timeout = 600000)
  public void testAppReregisterOnRMWorkPreservingRestart() throws Exception {
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);

    // start RM
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 15120, rm1.getResourceTrackerService());
    nm1.registerNode();

    // create app and launch the AM
    RMApp app0 = rm1.submitApp(200);
    MockAM am0 = MockRM.launchAM(app0, rm1, nm1);
    // Issuing registerAppAttempt() before and after RM restart to confirm
    // registerApplicationMaster() is idempotent.
    am0.registerAppAttempt();

    // start new RM
    rm2 = new MockRM(conf, memStore);
    rm2.start();
    rm2.waitForState(app0.getApplicationId(), RMAppState.ACCEPTED);
    rm2.waitForState(am0.getApplicationAttemptId(), RMAppAttemptState.LAUNCHED);

    am0.setAMRMProtocol(rm2.getApplicationMasterService(), rm2.getRMContext());
    // retry registerApplicationMaster() after RM restart.
    am0.registerAppAttempt(true);

    rm2.waitForState(app0.getApplicationId(), RMAppState.RUNNING);
    rm2.waitForState(am0.getApplicationAttemptId(), RMAppAttemptState.RUNNING);
  }
  
  @Test (timeout = 30000)
  public void testAMContainerStatusWithRMRestart() throws Exception {  
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1_1 = rm1.submitApp(1024);
    MockAM am1_1 = MockRM.launchAndRegisterAM(app1_1, rm1, nm1);
    
    RMAppAttempt attempt0 = app1_1.getCurrentAppAttempt();
    AbstractYarnScheduler scheduler =
        ((AbstractYarnScheduler) rm1.getResourceScheduler());
    
    Assert.assertTrue(scheduler.getRMContainer(
        attempt0.getMasterContainer().getId()).isAMContainer());

    // Re-start RM
    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());

    List<NMContainerStatus> am1_1Containers =
        createNMContainerStatusForApp(am1_1);
    nm1.registerNode(am1_1Containers, null);

    // Wait for RM to settle down on recovering containers;
    waitForNumContainersToRecover(2, rm2, am1_1.getApplicationAttemptId());

    scheduler = ((AbstractYarnScheduler) rm2.getResourceScheduler());
    Assert.assertTrue(scheduler.getRMContainer(
        attempt0.getMasterContainer().getId()).isAMContainer());
  }

  @Test (timeout = 20000)
  public void testRecoverSchedulerAppAndAttemptSynchronously() throws Exception {
    // start RM
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 15120, rm1.getResourceTrackerService());
    nm1.registerNode();

    // create app and launch the AM
    RMApp app0 = rm1.submitApp(200);
    MockAM am0 = MockRM.launchAndRegisterAM(app0, rm1, nm1);

    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    // scheduler app/attempt is immediately available after RM is re-started.
    Assert.assertNotNull(rm2.getResourceScheduler().getSchedulerAppInfo(
      am0.getApplicationAttemptId()));

    // getTransferredContainers should not throw NPE.
    ((AbstractYarnScheduler) rm2.getResourceScheduler())
      .getTransferredContainers(am0.getApplicationAttemptId());

    List<NMContainerStatus> containers = createNMContainerStatusForApp(am0);
    nm1.registerNode(containers, null);
    waitForNumContainersToRecover(2, rm2, am0.getApplicationAttemptId());
  }

  // Test if RM on recovery receives the container release request from AM
  // before it receives the container status reported by NM for recovery. this
  // container should not be recovered.
  @Test (timeout = 50000)
  public void testReleasedContainerNotRecovered() throws Exception {
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    MockNM nm1 = new MockNM("h1:1234", 15120, rm1.getResourceTrackerService());
    nm1.registerNode();
    rm1.start();

    RMApp app1 = rm1.submitApp(1024);
    final MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    // Re-start RM
    conf.setInt(YarnConfiguration.RM_NM_EXPIRY_INTERVAL_MS, 8000);
    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    rm2.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    am1.setAMRMProtocol(rm2.getApplicationMasterService(), rm2.getRMContext());
    am1.registerAppAttempt(true);

    // try to release a container before the container is actually recovered.
    final ContainerId runningContainer =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 2);
    am1.allocate(null, Arrays.asList(runningContainer));

    // send container statuses to recover the containers
    List<NMContainerStatus> containerStatuses =
        createNMContainerStatusForApp(am1);
    nm1.registerNode(containerStatuses, null);

    // only the am container should be recovered.
    waitForNumContainersToRecover(1, rm2, am1.getApplicationAttemptId());

    final AbstractYarnScheduler scheduler =
        (AbstractYarnScheduler) rm2.getResourceScheduler();
    // cached release request is cleaned.
    // assertFalse(scheduler.getPendingRelease().contains(runningContainer));

    AllocateResponse response = am1.allocate(null, null);
    // AM gets notified of the completed container.
    boolean receivedCompletedContainer = false;
    for (ContainerStatus status : response.getCompletedContainersStatuses()) {
      if (status.getContainerId().equals(runningContainer)) {
        receivedCompletedContainer = true;
      }
    }
    assertTrue(receivedCompletedContainer);

    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      public Boolean get() {
        // release cache is cleaned up and previous running container is not
        // recovered
        return scheduler.getApplicationAttempt(am1.getApplicationAttemptId())
          .getPendingRelease().isEmpty()
            && scheduler.getRMContainer(runningContainer) == null;
      }
    }, 1000, 20000);
  }

  private void assertMetrics(QueueMetrics qm, int appsSubmitted,
      int appsPending, int appsRunning, int appsCompleted,
      int allocatedContainers, int availableMB, int availableVirtualCores,
      int allocatedMB, int allocatedVirtualCores) {
    assertEquals(appsSubmitted, qm.getAppsSubmitted());
    assertEquals(appsPending, qm.getAppsPending());
    assertEquals(appsRunning, qm.getAppsRunning());
    assertEquals(appsCompleted, qm.getAppsCompleted());
    assertEquals(allocatedContainers, qm.getAllocatedContainers());
    assertEquals(availableMB, qm.getAvailableMB());
    assertEquals(availableVirtualCores, qm.getAvailableVirtualCores());
    assertEquals(allocatedMB, qm.getAllocatedMB());
    assertEquals(allocatedVirtualCores, qm.getAllocatedVirtualCores());
  }

  public static void waitForNumContainersToRecover(int num, MockRM rm,
      ApplicationAttemptId attemptId) throws Exception {
    AbstractYarnScheduler scheduler =
        (AbstractYarnScheduler) rm.getResourceScheduler();
    SchedulerApplicationAttempt attempt =
        scheduler.getApplicationAttempt(attemptId);
    while (attempt == null) {
      System.out.println("Wait for scheduler attempt " + attemptId
          + " to be created");
      Thread.sleep(200);
      attempt = scheduler.getApplicationAttempt(attemptId);
    }
    while (attempt.getLiveContainers().size() < num) {
      System.out.println("Wait for " + num
          + " containers to recover. currently: "
          + attempt.getLiveContainers().size());
      Thread.sleep(200);
    }
  }

  @Test (timeout = 20000)
  public void testNewContainersNotAllocatedDuringSchedulerRecovery()
      throws Exception {
    conf.setLong(
      YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_SCHEDULING_WAIT_MS, 4000);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    // Restart RM
    rm2 = new MockRM(conf, memStore);
    rm2.start();
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    nm1.registerNode();
    ControlledClock clock = new ControlledClock(new SystemClock());
    long startTime = System.currentTimeMillis();
    ((RMContextImpl)rm2.getRMContext()).setSystemClock(clock);
    am1.setAMRMProtocol(rm2.getApplicationMasterService(), rm2.getRMContext());
    am1.registerAppAttempt(true);
    rm2.waitForState(app1.getApplicationId(), RMAppState.RUNNING);

    // AM request for new containers
    am1.allocate("127.0.0.1", 1000, 1, new ArrayList<ContainerId>());

    List<Container> containers = new ArrayList<Container>();
    clock.setTime(startTime + 2000);
    nm1.nodeHeartbeat(true);

    // sleep some time as allocation happens asynchronously.
    Thread.sleep(3000);
    containers.addAll(am1.allocate(new ArrayList<ResourceRequest>(),
      new ArrayList<ContainerId>()).getAllocatedContainers());
    // container is not allocated during scheduling recovery.
    Assert.assertTrue(containers.isEmpty());

    clock.setTime(startTime + 8000);
    nm1.nodeHeartbeat(true);
    // Container is created after recovery is done.
    while (containers.isEmpty()) {
      containers.addAll(am1.allocate(new ArrayList<ResourceRequest>(),
        new ArrayList<ContainerId>()).getAllocatedContainers());
      Thread.sleep(500);
    }
  }

  /**
   * Testing to confirm that retried finishApplicationMaster() doesn't throw
   * InvalidApplicationMasterRequest before and after RM restart.
   */
  @Test (timeout = 20000)
  public void testRetriedFinishApplicationMasterRequest()
      throws Exception {
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);

    // start RM
    rm1 = new MockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 15120, rm1.getResourceTrackerService());
    nm1.registerNode();

    // create app and launch the AM
    RMApp app0 = rm1.submitApp(200);
    MockAM am0 = MockRM.launchAM(app0, rm1, nm1);

    am0.registerAppAttempt();

    // Emulating following a scenario:
    // RM1 saves the app in RMStateStore and then crashes,
    // FinishApplicationMasterResponse#isRegistered still return false,
    // so AM still retry the 2nd RM
    MockRM.finishAMAndVerifyAppState(app0, rm1, nm1, am0);


    // start new RM
    rm2 = new MockRM(conf, memStore);
    rm2.start();

    am0.setAMRMProtocol(rm2.getApplicationMasterService(), rm2.getRMContext());
    am0.unregisterAppAttempt(false);
  }

  @Test (timeout = 30000)
  public void testAppFailedToRenewTokenOnRecovery() throws Exception {
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
      "kerberos");
    conf.setInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, 1);
    UserGroupInformation.setConfiguration(conf);
    MemoryRMStateStore memStore = new MemoryRMStateStore();
    memStore.init(conf);
    MockRM rm1 = new TestSecurityMockRM(conf, memStore);
    rm1.start();
    MockNM nm1 =
        new MockNM("127.0.0.1:1234", 8192, rm1.getResourceTrackerService());
    nm1.registerNode();
    RMApp app1 = rm1.submitApp(200);
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm1, nm1);

    MockRM rm2 = new TestSecurityMockRM(conf, memStore) {
      protected DelegationTokenRenewer createDelegationTokenRenewer() {
        return new DelegationTokenRenewer() {
          @Override
          public void addApplicationSync(ApplicationId applicationId,
              Credentials ts, boolean shouldCancelAtEnd, String user)
              throws IOException {
            throw new IOException("Token renew failed !!");
          }
        };
      }
    };
    nm1.setResourceTrackerService(rm2.getResourceTrackerService());
    rm2.start();
    NMContainerStatus containerStatus =
        TestRMRestart.createNMContainerStatus(am1.getApplicationAttemptId(), 1,
          ContainerState.RUNNING);
    nm1.registerNode(Arrays.asList(containerStatus), null);

    // am re-register
    rm2.waitForState(app1.getApplicationId(), RMAppState.ACCEPTED);
    am1.setAMRMProtocol(rm2.getApplicationMasterService(), rm2.getRMContext());
    am1.registerAppAttempt(true);
    rm2.waitForState(app1.getApplicationId(), RMAppState.RUNNING);

    // Because the token expired, am could crash.
    nm1.nodeHeartbeat(am1.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    rm2.waitForState(am1.getApplicationAttemptId(), RMAppAttemptState.FAILED);
    rm2.waitForState(app1.getApplicationId(), RMAppState.FAILED);
  }
}
