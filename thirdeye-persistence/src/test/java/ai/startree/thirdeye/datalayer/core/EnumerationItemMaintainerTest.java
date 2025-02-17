/*
 * Copyright 2023 StarTree Inc
 *
 * Licensed under the StarTree Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.startree.ai/legal/startree-community-license
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT * WARRANTIES OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing permissions and limitations under
 * the License.
 */

package ai.startree.thirdeye.datalayer.core;

import static ai.startree.thirdeye.spi.util.SpiUtils.alertRef;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import ai.startree.thirdeye.datalayer.MySqlTestDatabase;
import ai.startree.thirdeye.spi.datalayer.bao.AnomalyManager;
import ai.startree.thirdeye.spi.datalayer.bao.EnumerationItemManager;
import ai.startree.thirdeye.spi.datalayer.bao.SubscriptionGroupManager;
import ai.startree.thirdeye.spi.datalayer.dto.AlertAssociationDto;
import ai.startree.thirdeye.spi.datalayer.dto.AnomalyDTO;
import ai.startree.thirdeye.spi.datalayer.dto.EnumerationItemDTO;
import ai.startree.thirdeye.spi.datalayer.dto.SubscriptionGroupDTO;
import com.google.inject.Injector;
import java.util.List;
import java.util.Map;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class EnumerationItemMaintainerTest {

  public static final long ALERT_ID = 1234L;
  private AnomalyManager anomalyManager;
  private SubscriptionGroupManager subscriptionGroupManager;
  private EnumerationItemMaintainer enumerationItemMaintainer;
  private EnumerationItemManager enumerationItemManager;

  private static EnumerationItemDTO ei(final String name) {
    return ei(name, Map.of());
  }

  private static EnumerationItemDTO ei(final String name, final Map<String, Object> m) {
    return new EnumerationItemDTO().setName(name).setParams(m);
  }

  private static EnumerationItemDTO sourceEi() {
    return ei("ei1", Map.of("a", 1))
        .setAlert(alertRef(ALERT_ID));
  }

  private static AnomalyDTO anomaly(final long startTime, final long endTime) {
    return new AnomalyDTO()
        .setStartTime(startTime)
        .setEndTime(endTime);
  }

  private static AnomalyDTO anomaly() {
    return anomaly(1000L, 2000L);
  }

  @BeforeClass
  void beforeClass() {
    final Injector injector = MySqlTestDatabase.sharedInjector();
    anomalyManager = injector.getInstance(AnomalyManager.class);
    subscriptionGroupManager = injector.getInstance(SubscriptionGroupManager.class);
    enumerationItemMaintainer = injector.getInstance(EnumerationItemMaintainer.class);
    enumerationItemManager = injector.getInstance(EnumerationItemManager.class);
  }

  @AfterMethod
  void afterClass() {
    enumerationItemManager.findAll().forEach(enumerationItemManager::delete);
    anomalyManager.findAll().forEach(anomalyManager::delete);
    subscriptionGroupManager.findAll().forEach(subscriptionGroupManager::delete);
  }

  @Test
  public void testFindExistingOrCreateCaseNewCreation() {
    final EnumerationItemDTO source = sourceEi();
    final EnumerationItemDTO created = enumerationItemMaintainer.findExistingOrCreate(source, null);
    assertThat(created).isNotNull();
    assertThat(created.getId()).isNotNull();
    assertThat(created.getName()).isEqualTo(source.getName());
    assertThat(created.getParams()).isEqualTo(source.getParams());
    assertThat(created.getAlert()).isNotNull();
    assertThat(created.getAlert().getId()).isEqualTo(source.getAlert().getId());
  }

  @Test
  public void testFindExistingOrCreateCaseExistingWithAlert() {
    final var source = sourceEi();

    final var existing = ei(source.getName(), source.getParams())
        .setAlert(source.getAlert());
    enumerationItemManager.save(existing);

    final var existingOtherName = ei("someOtherName", source.getParams())
        .setAlert(source.getAlert());
    enumerationItemManager.save(existingOtherName);

    final var existingOtherParams = ei(source.getName(), Map.of("blah", 1))
        .setAlert(source.getAlert());
    enumerationItemManager.save(existingOtherParams);

    final var found = enumerationItemMaintainer.findExistingOrCreate(source, null);
    assertThat(found).isNotNull();
    assertThat(found.getId()).isNotNull();
    assertThat(found.getName()).isEqualTo(source.getName());
    assertThat(found.getParams()).isEqualTo(source.getParams());
    assertThat(found.getAlert()).isNotNull();
    assertThat(found.getAlert().getId()).isEqualTo(source.getAlert().getId());

    assertThat(found.getId()).isEqualTo(existing.getId());
    assertThat(found.getId()).isNotEqualTo(existingOtherName.getId());
    assertThat(found.getId()).isNotEqualTo(existingOtherParams.getId());
  }

  /**
   * Test whether legacy enumeration items are migrated correctly.
   */
  @Test
  public void testFindExistingOrCreateCaseAnomalyMigration() {
    final EnumerationItemDTO source = sourceEi();

    final var ei1 = ei(source.getName(), source.getParams());
    enumerationItemManager.save(ei1);

    final var ei2 = ei("ei2", Map.of("a", 1));
    enumerationItemManager.save(ei2);

    final var a1 = anomaly()
        .setDetectionConfigId(ALERT_ID)
        .setEnumerationItem(ei1);
    anomalyManager.save(a1);

    final var a2 = anomaly()
        .setDetectionConfigId(ALERT_ID)
        .setEnumerationItem(ei2);
    anomalyManager.save(a2);

    final var a3 = anomaly()
        .setDetectionConfigId(5678L);
    anomalyManager.save(a3);

    final var a4 = anomaly()
        .setDetectionConfigId(5678L)
        .setEnumerationItem(ei1);
    anomalyManager.save(a4);

    final var sg1 = new SubscriptionGroupDTO()
        .setName("sg1")
        .setAlertAssociations(List.of(new AlertAssociationDto()
            .setAlert(alertRef(ALERT_ID))
            .setEnumerationItem(ei1)));
    subscriptionGroupManager.save(sg1);

    final var sg2 = new SubscriptionGroupDTO()
        .setName("sg2")
        .setAlertAssociations(List.of(new AlertAssociationDto()
            .setAlert(alertRef(ALERT_ID))
            .setEnumerationItem(ei2)));
    subscriptionGroupManager.save(sg2);

    final var sg3 = new SubscriptionGroupDTO()
        .setName("sg3")
        .setAlertAssociations(List.of(new AlertAssociationDto()
            .setAlert(alertRef(5678L))
            .setEnumerationItem(ei1)));
    subscriptionGroupManager.save(sg3);

    final EnumerationItemDTO migrated = enumerationItemMaintainer.findExistingOrCreate(source, null);

    assertThat(migrated).isNotNull();
    assertThat(migrated.getId()).isNotNull();
    assertThat(migrated.getName()).isEqualTo(source.getName());
    assertThat(migrated.getParams()).isEqualTo(source.getParams());
    assertThat(migrated.getAlert()).isNotNull();
    assertThat(migrated.getAlert().getId()).isEqualTo(source.getAlert().getId());

    assertThat(migrated.getId()).isNotEqualTo(ei1.getId());

    /* Test anomaly migration */
    final var a1Updated = anomalyManager.findById(a1.getId());
    assertThat(a1Updated.getEnumerationItem().getId()).isEqualTo(migrated.getId());

    assertThat(anomalyManager.findById(a2.getId())
        .getEnumerationItem()
        .getId()).isEqualTo(ei2.getId());

    assertThat(anomalyManager.findById(a3.getId())
        .getEnumerationItem())
        .isNull();

    assertThat(anomalyManager.findById(a4.getId())
        .getEnumerationItem()
        .getId()).isEqualTo(ei1.getId());

    /* Test subscription group migration */
    final var sg1Updated = subscriptionGroupManager.findById(sg1.getId());
    assertThat(sg1Updated.getAlertAssociations().get(0).getEnumerationItem().getId())
        .isEqualTo(migrated.getId());

    final var sg2Updated = subscriptionGroupManager.findById(sg2.getId());
    assertThat(sg2Updated.getAlertAssociations().get(0).getEnumerationItem().getId())
        .isEqualTo(ei2.getId());

    final var sg3Updated = subscriptionGroupManager.findById(sg3.getId());
    assertThat(sg3Updated.getAlertAssociations().get(0).getEnumerationItem().getId())
        .isEqualTo(ei1.getId());
  }

  @Test
  public void testFindByIdKeys() {
    final EnumerationItemDTO source = sourceEi();

    final var ei1 = ei(source.getName(), source.getParams())
        .setAlert(source.getAlert());
    enumerationItemManager.save(ei1);

    final var ei2 = ei("ei2", Map.of("a", 2))
        .setAlert(source.getAlert());
    enumerationItemManager.save(ei2);

    final var ei3 = ei("ei3", Map.of("a", 3))
        .setAlert(source.getAlert());
    enumerationItemManager.save(ei3);

    final var ei4 = ei("ei4", Map.of("a", 1))
        .setAlert(alertRef(5678L));
    enumerationItemManager.save(ei4);

    final var ei5 = ei("ei5", Map.of("a", 2))
        .setAlert(alertRef(5678L));
    enumerationItemManager.save(ei5);

    final var ei6 = ei("ei6", Map.of("a", 1));
    enumerationItemManager.save(ei6);

    final EnumerationItemDTO sourceNew = sourceEi().setParams(Map.of("a", 5));
    assertThat(enumerationItemMaintainer.findUsingIdKeys(sourceNew, List.of("a")))
        .isNull();

    final EnumerationItemDTO found = enumerationItemMaintainer.findUsingIdKeys(source, List.of("a"));
    assertThat(found).isNotNull();
    assertThat(found.getId()).isEqualTo(ei1.getId());
  }

  @Test
  public void testWithAugmentedParamsSameIdKeys() {
    final String key = "key";
    final EnumerationItemDTO source = ei("ei1", Map.of(key, 1))
        .setAlert(alertRef(ALERT_ID));

    final var ei1 = ei(source.getName(), source.getParams())
        .setAlert(source.getAlert());
    enumerationItemManager.save(ei1);

    final var fromDb = enumerationItemMaintainer.findExistingOrCreate(source, List.of(key));
    assertThat(fromDb.getId()).isEqualTo(ei1.getId());
    assertThat(fromDb.getParams()).isEqualTo(ei1.getParams());

    // modify source params keeping the same id keys
    source.setParams(Map.of(key, 1, "b", 2));
    final var fromDb2 = enumerationItemMaintainer.findExistingOrCreate(source, List.of(key));
    assertThat(fromDb2.getId()).isEqualTo(ei1.getId());

    // ei1 params should no longer match the new params from db
    assertThat(fromDb2.getParams()).isNotEqualTo(ei1.getParams());

    // but they should match the source params
    assertThat(fromDb2.getParams()).isEqualTo(source.getParams());

    final var ei1Updated = enumerationItemManager.findById(ei1.getId());
    assertThat(ei1Updated.getId()).isEqualTo(ei1.getId());
    assertThat(ei1Updated.getParams()).isEqualTo(source.getParams());
  }
}
