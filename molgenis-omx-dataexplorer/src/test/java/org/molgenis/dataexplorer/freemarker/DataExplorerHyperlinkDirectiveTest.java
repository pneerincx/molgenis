package org.molgenis.dataexplorer.freemarker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import org.molgenis.data.DataService;
import org.molgenis.dataexplorer.controller.DataExplorerController;
import org.molgenis.framework.ui.MolgenisPlugin;
import org.molgenis.framework.ui.MolgenisPluginRegistry;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Maps;

import freemarker.core.Environment;
import freemarker.template.Template;
import freemarker.template.TemplateDirectiveBody;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModel;

public class DataExplorerHyperlinkDirectiveTest
{
	private DataService dataService;
	private DataExplorerHyperlinkDirective directive;
	private StringWriter envWriter;
	private Template fakeTemplate;

	@BeforeMethod
	public void setUp()
	{
		MolgenisPluginRegistry mpr = mock(MolgenisPluginRegistry.class);
		when(mpr.getPlugin(DataExplorerController.ID)).thenReturn(
				new MolgenisPlugin("dataex", "dataex", "dataex", "/menu/data/dataex"));

		dataService = mock(DataService.class);
		when(dataService.hasRepository("thedataset")).thenReturn(true);

		directive = new DataExplorerHyperlinkDirective(mpr, dataService);
		envWriter = new StringWriter();
		fakeTemplate = Template.getPlainTextTemplate("name", "content", null);
	}

	@Test
	public void execute() throws TemplateException, IOException
	{
		when(dataService.hasRepository("thedataset")).thenReturn(true);

		Map<String, String> params = Maps.newHashMap();
		params.put("entityName", "thedataset");
		params.put("class", "class1 class2");

		directive.execute(new Environment(fakeTemplate, null, envWriter), params, new TemplateModel[0],
				new TemplateDirectiveBody()
				{
					@Override
					public void render(Writer out) throws TemplateException, IOException
					{
						out.write("explore data");
					}

				});

		assertEquals(envWriter.toString(),
				"<a href='/menu/data/dataex?dataset=thedataset' class='class1 class2' >explore data</a>");
	}

	@Test
	public void executeWithMissingDataset() throws TemplateException, IOException
	{
		when(dataService.hasRepository("thedataset")).thenReturn(false);

		Map<String, String> params = Maps.newHashMap();
		params.put("entityName", "thedataset");
		params.put("class", "class1 class2");
		params.put("alternativeText", "alt");

		directive.execute(new Environment(fakeTemplate, null, envWriter), params, new TemplateModel[0],
				new TemplateDirectiveBody()
				{
					@Override
					public void render(Writer out) throws TemplateException, IOException
					{
						out.write("explore data");
					}

				});

		assertEquals(envWriter.toString(), "alt");
	}
}
