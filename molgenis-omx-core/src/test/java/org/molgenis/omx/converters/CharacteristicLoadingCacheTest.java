package org.molgenis.omx.converters;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.molgenis.data.DataService;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.omx.observ.Characteristic;
import org.testng.annotations.Test;

public class CharacteristicLoadingCacheTest
{

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void CharacteristicLoadingCache()
	{
		new CharacteristicLoadingCache(null);
	}

	@Test
	public void findCharacteristic() throws ExecutionException
	{
		DataService dataService = mock(DataService.class);

		CharacteristicLoadingCache characteristicLoadingCache = new CharacteristicLoadingCache(dataService);

		Characteristic ch1 = mock(Characteristic.class);
		when(ch1.getIdentifier()).thenReturn("ch1");
		when(ch1.getId()).thenReturn(1);

		when(
				dataService.findOne(Characteristic.ENTITY_NAME, new QueryImpl().eq(Characteristic.IDENTIFIER, "ch1"),
						Characteristic.class)).thenReturn(ch1);

		when(
				dataService.findAll(Characteristic.ENTITY_NAME,
						new QueryImpl().in(Characteristic.IDENTIFIER, Arrays.asList("ch1")), Characteristic.class))
				.thenReturn(Arrays.<Characteristic> asList(ch1));

		when(dataService.findOne(Characteristic.ENTITY_NAME, 1, Characteristic.class)).thenReturn(ch1);

		assertEquals(characteristicLoadingCache.findCharacteristic("ch1"), ch1);
	}

	@Test
	public void findCharacteristics()
	{
		DataService dataService = mock(DataService.class);
		CharacteristicLoadingCache characteristicLoadingCache = new CharacteristicLoadingCache(dataService);
		Characteristic ch1 = mock(Characteristic.class);
		when(ch1.getIdentifier()).thenReturn("ch1");
		when(ch1.getId()).thenReturn(1);

		Characteristic ch2 = mock(Characteristic.class);
		when(ch2.getIdentifier()).thenReturn("ch2");
		when(ch2.getId()).thenReturn(2);

		when(
				dataService.findOne(Characteristic.ENTITY_NAME, new QueryImpl().eq(Characteristic.IDENTIFIER, "ch1"),
						Characteristic.class)).thenReturn(ch1);

		when(
				dataService.findOne(Characteristic.ENTITY_NAME, new QueryImpl().eq(Characteristic.IDENTIFIER, "ch2"),
						Characteristic.class)).thenReturn(ch2);

		when(
				dataService.findAll(Characteristic.ENTITY_NAME,
						new QueryImpl().in(Characteristic.IDENTIFIER, Arrays.asList("ch1", "ch2")),
						Characteristic.class)).thenReturn(Arrays.<Characteristic> asList(ch1, ch2));

		when(dataService.findOne(Characteristic.ENTITY_NAME, 1, Characteristic.class)).thenReturn(ch1);
		when(dataService.findOne(Characteristic.ENTITY_NAME, 2, Characteristic.class)).thenReturn(ch2);

		assertEquals(characteristicLoadingCache.findCharacteristics(Arrays.asList("ch1", "ch2")),
				Arrays.asList(ch1, ch2));
	}

}
