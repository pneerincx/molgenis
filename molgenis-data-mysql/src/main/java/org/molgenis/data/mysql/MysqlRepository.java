package org.molgenis.data.mysql;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.log4j.Logger;
import org.molgenis.MolgenisFieldTypes;
import org.molgenis.data.AggregateResult;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.CrudRepository;
import org.molgenis.data.DataConverter;
import org.molgenis.data.DatabaseAction;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.Manageable;
import org.molgenis.data.Query;
import org.molgenis.data.QueryRule;
import org.molgenis.data.Queryable;
import org.molgenis.data.Repository;
import org.molgenis.data.Writable;
import org.molgenis.data.support.MapEntity;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.fieldtypes.BoolField;
import org.molgenis.fieldtypes.FieldType;
import org.molgenis.fieldtypes.IntField;
import org.molgenis.fieldtypes.MrefField;
import org.molgenis.fieldtypes.StringField;
import org.molgenis.fieldtypes.TextField;
import org.molgenis.fieldtypes.XrefField;
import org.molgenis.model.MolgenisModelException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class MysqlRepository implements Repository, Writable, Queryable, Manageable, CrudRepository
{
	private static final Logger logger = Logger.getLogger(MysqlRepository.class);

	public static final int BATCH_SIZE = 100000;
	private final EntityMetaData metaData;
	private final MysqlRepositoryCollection repositoryCollection;
	private DataSource ds;
	private JdbcTemplate jdbcTemplate;

	protected MysqlRepository(MysqlRepositoryCollection collection, EntityMetaData metaData)
	{
		if (collection == null) throw new IllegalArgumentException("DataSource is null");
		if (metaData == null) throw new IllegalArgumentException("metaData is null");
		this.metaData = metaData;
		this.repositoryCollection = collection;
		this.ds = collection.getDataSource();
		this.jdbcTemplate = new JdbcTemplate(ds);
	}

	@Autowired
	public void setDataSource(DataSource dataSource)
	{
		this.ds = dataSource;
		this.jdbcTemplate = new JdbcTemplate(ds);
		logger.debug("set:" + dataSource);
	}

	@Override
	public void drop()
	{
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			if (att.getDataType() instanceof MrefField)
			{
				jdbcTemplate.execute("DROP TABLE IF EXISTS " + getEntityMetaData().getName() + "_" + att.getName());
			}
		}
		jdbcTemplate.execute(this.getDropSql());
	}

	protected String getDropSql()
	{
		return "DROP TABLE IF EXISTS " + getEntityMetaData().getName();
	}

	@Override
	public void create()
	{
		try
		{
			jdbcTemplate.execute(this.getCreateSql());
			for (String fkeySql : this.getCreateFKeySql())
				jdbcTemplate.execute(fkeySql);
			// add mref tables
			for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			{
				if (att.getDataType() instanceof MrefField)
				{
					jdbcTemplate.execute(this.getMrefCreateSql(att));
				}
			}

		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	protected String getMrefCreateSql(AttributeMetaData att) throws MolgenisModelException
	{
		AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		return " CREATE TABLE " + getEntityMetaData().getName() + "_" + att.getName() + "(" + idAttribute.getName()
				+ " " + idAttribute.getDataType().getMysqlType() + " NOT NULL, " + att.getName() + " "
				+ att.getRefEntity().getIdAttribute().getDataType().getMysqlType() + " NOT NULL, FOREIGN KEY ("
				+ idAttribute.getName() + ") REFERENCES " + getEntityMetaData().getName() + "(" + idAttribute.getName()
				+ ") ON DELETE CASCADE, FOREIGN KEY (" + att.getName() + ") REFERENCES " + att.getRefEntity().getName()
				+ "(" + att.getRefEntity().getIdAttribute().getName() + ") ON DELETE CASCADE);";
	}

	protected String getCreateSql() throws MolgenisModelException
	{
		StringBuilder sql = new StringBuilder();
		sql.append("CREATE TABLE IF NOT EXISTS ").append(getEntityMetaData().getName()).append('(');
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			if (!(att.getDataType() instanceof MrefField))
			{
				sql.append(att.getName()).append(' ');
				// xref adopt type of the identifier of referenced entity
				if (att.getDataType() instanceof XrefField)
				{
					sql.append(att.getRefEntity().getIdAttribute().getDataType().getMysqlType());
				}
				else
				{
					sql.append(att.getDataType().getMysqlType());
				}
				// not null
				if (!att.isNillable())
				{
					sql.append(" NOT NULL");
				}
				// int + auto = auto_increment
				if (att.getDataType().equals(MolgenisFieldTypes.INT) && att.isAuto())
				{
					sql.append(" AUTO_INCREMENT");
				}
				sql.append(", ");
			}
		}
		// primary key is first attribute unless otherwise indicate
		AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		if (idAttribute.getDataType() instanceof XrefField || idAttribute.getDataType() instanceof MrefField) throw new RuntimeException(
				"primary key(" + getEntityMetaData().getName() + "." + idAttribute.getName()
						+ ") cannot be XREF or MREF");
		if (idAttribute.isNillable() == true) throw new RuntimeException("primary key(" + getEntityMetaData().getName()
				+ "." + idAttribute.getName() + ") must be NOT NULL");
		sql.append("PRIMARY KEY (").append(getEntityMetaData().getIdAttribute().getName()).append(')');

		// close
		sql.append(") ENGINE=InnoDB;");

		return sql.toString();
	}

	protected List<String> getCreateFKeySql()
	{
		List<String> sql = new ArrayList<String>();
		// foreign keys
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			if (att.getDataType().equals(MolgenisFieldTypes.XREF))
			{
				sql.add(new StringBuilder().append("ALTER TABLE ").append(getEntityMetaData().getName())
						.append(" ADD FOREIGN KEY (").append(att.getName()).append(") REFERENCES ")
						.append(att.getRefEntity().getName()).append('(')
						.append(att.getRefEntity().getIdAttribute().getName()).append(')').toString());
			}
		return sql;
	}

	@Override
	public String getName()
	{
		return metaData.getName();
	}

	@Override
	public EntityMetaData getEntityMetaData()
	{
		return metaData;
	}

	@Override
	public <E extends Entity> Iterable<E> iterator(Class<E> clazz)
	{
		throw new UnsupportedOperationException();
	}

	protected String iteratorSql()
	{
		StringBuilder sql = new StringBuilder("SELECT ");
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			sql.append(att.getName()).append(", ");
		}
		if (sql.charAt(sql.length() - 1) == ' ' && sql.charAt(sql.length() - 2) == ',') sql.setLength(sql.length() - 2);
		else sql.append('*');
		sql.append(" FROM ").append(getEntityMetaData().getName());
		return sql.toString();
	}

	@Override
	public String getUrl()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public void close() throws IOException
	{
	}

	@Override
	public Iterator<Entity> iterator()
	{
		return findAll(new QueryImpl()).iterator();
	}

	@Override
	public void add(Entity entity)
	{
		if (entity == null) throw new RuntimeException("MysqlRepository.add() failed: entity was null");
		this.add(Arrays.asList(new Entity[]
		{ entity }));
	}

	protected String getInsertSql()
	{
		StringBuilder sql = new StringBuilder();
		sql.append("INSERT INTO ").append(this.getName()).append(" (");
		StringBuilder params = new StringBuilder();
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			if (!(att.getDataType() instanceof MrefField))
			{
				sql.append(att.getName()).append(", ");
				params.append("?, ");
			}
		if (sql.charAt(sql.length() - 1) == ' ' && sql.charAt(sql.length() - 2) == ',')
		{
			sql.setLength(sql.length() - 2);
			params.setLength(params.length() - 2);
		}
		sql.append(") VALUES (").append(params).append(")");
		return sql.toString();
	}

	@Override
	public Integer add(Iterable<? extends Entity> entities)
	{
		AtomicInteger count = new AtomicInteger(0);

		// TODO, split in subbatches
		final List<Entity> batch = new ArrayList<Entity>();
		if (entities != null) for (Entity e : entities)
		{
			batch.add(e);
			count.addAndGet(1);
		}
		final AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		final Map<String, List<Entity>> mrefs = new HashMap<String, List<Entity>>();

		jdbcTemplate.batchUpdate(this.getInsertSql(), new BatchPreparedStatementSetter()
		{
			@Override
			public void setValues(PreparedStatement preparedStatement, int rowIndex) throws SQLException
			{
				int fieldIndex = 1;
				for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
				{
					// create the mref records
					if (att.getDataType() instanceof MrefField)
					{
						if (mrefs.get(att.getName()) == null) mrefs.put(att.getName(), new ArrayList<Entity>());
						if (batch.get(rowIndex).get(att.getName()) != null)
						{
							for (Object val : batch.get(rowIndex).getList(att.getName()))
							{
								Entity mref = new MapEntity();
								mref.set(idAttribute.getName(), batch.get(rowIndex).get(idAttribute.getName()));
								mref.set(att.getName(), val);
								mrefs.get(att.getName()).add(mref);
							}
						}
					}
					else
					{
						// default value, if any
						if (batch.get(rowIndex).get(att.getName()) == null)
						{
							preparedStatement.setObject(fieldIndex++, att.getDefaultValue());
						}
						else
						{
							if (att.getDataType() instanceof XrefField)
							{
								preparedStatement.setObject(fieldIndex++, att.getRefEntity().getIdAttribute()
										.getDataType().convert(batch.get(rowIndex).get(att.getName())));
							}
							else
							{
								preparedStatement.setObject(fieldIndex++,
										att.getDataType().convert(batch.get(rowIndex).get(att.getName())));
							}
						}
					}
				}
			}

			@Override
			public int getBatchSize()
			{
				return batch.size();
			}
		});

		// add mrefs as well
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			if (att.getDataType() instanceof MrefField)
			{
				addMrefs(mrefs.get(att.getName()), att);
			}
		}

		return count.get();
	}

	private void removeMrefs(final List<Object> ids, final AttributeMetaData att)
	{
		final AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		String mrefSql = "DELETE FROM " + getEntityMetaData().getName() + "_" + att.getName() + " WHERE "
				+ idAttribute.getName() + "= ?";
		jdbcTemplate.batchUpdate(mrefSql, new BatchPreparedStatementSetter()
		{
			@Override
			public void setValues(PreparedStatement preparedStatement, int i) throws SQLException
			{
				preparedStatement.setObject(1, ids.get(i));
			}

			@Override
			public int getBatchSize()
			{
				return ids.size();
			}
		});
	}

	private void addMrefs(final List<Entity> mrefs, final AttributeMetaData att)
	{

		final AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		final AttributeMetaData refAttribute = att.getRefEntity().getIdAttribute();
		String mrefSql = "INSERT INTO " + getEntityMetaData().getName() + "_" + att.getName() + " ("
				+ idAttribute.getName() + "," + att.getName() + ") VALUES (?,?)";
		jdbcTemplate.batchUpdate(mrefSql, new BatchPreparedStatementSetter()
		{
			@Override
			public void setValues(PreparedStatement preparedStatement, int i) throws SQLException
			{
				logger.debug("mref: " + mrefs.get(i).get(idAttribute.getName()) + ", "
						+ mrefs.get(i).get(att.getName()));

				preparedStatement.setObject(1, mrefs.get(i).get(idAttribute.getName()));
				Object value = mrefs.get(i).get(att.getName());
				if (value instanceof Entity)
				{
					preparedStatement.setObject(2,
							refAttribute.getDataType().convert(((Entity) value).get(idAttribute.getName())));
				}
				else
				{
					preparedStatement.setObject(2, refAttribute.getDataType().convert(value));
				}
			}

			@Override
			public int getBatchSize()
			{
				return mrefs.size();
			}
		});
	}

	@Override
	public void flush()
	{

	}

	@Override
	public void clearCache()
	{

	}

	@Override
	public long count(Query q)
	{
		String sql = getCountSql(q);
		logger.debug(sql);
		return jdbcTemplate.queryForObject(sql, Long.class);
	}

	protected String getSelectSql(Query q)
	{
		StringBuilder select = new StringBuilder("SELECT ");
		StringBuilder group = new StringBuilder();
		int count = 0;
		AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			if (count > 0) select.append(", ");

			// TODO needed when autoids are used to join
			if (att.getDataType() instanceof MrefField)
			{
				select.append("GROUP_CONCAT(DISTINCT(").append(att.getName()).append('.').append(att.getName())
						.append(")) AS ").append(att.getName());

			}
			else
			{
				select.append("this.").append(att.getName());
				if (group.length() > 0) group.append(", this.").append(att.getName());
				else group.append("this.").append(att.getName());
			}
			// }
			count++;

		}

		// from
		StringBuilder result = new StringBuilder().append(select).append(getFromSql());
		// where
		String where = getWhereSql(q);
		if (where.length() > 0) result.append(' ').append(where);
		// group by
		if (select.indexOf("GROUP_CONCAT") != -1 && group.length() > 0) result.append(" GROUP BY ").append(group);
		// order by
		result.append(' ').append(getSortSql(q));
		// limit
		if (q.getPageSize() > 0) result.append(" LIMIT ").append(q.getPageSize());
		if (q.getOffset() > 0) result.append(" OFFSET ").append(q.getOffset());
		return result.toString().trim();
	}

	@Override
	public Iterable<Entity> findAll(Query q)
	{
		String sql = getSelectSql(q);

		// tmp:
		logger.debug("query: " + q);
		logger.debug("sql: " + sql);

		return jdbcTemplate.query(sql, new EntityMapper());
	}

	@Override
	public <E extends Entity> Iterable<E> findAll(Query q, Class<E> clazz)
	{
		return null;
	}

	@Override
	public Entity findOne(Query q)
	{
		Iterator<Entity> iterator = findAll(q).iterator();
		if (iterator.hasNext()) return iterator.next();
		return null;
	}

	@Override
	public Entity findOne(Object id)
	{
		if (id == null) return null;
		return findOne(new QueryImpl().eq(getEntityMetaData().getIdAttribute().getName(), id));
	}

	@Override
	public Iterable<Entity> findAll(Iterable<Object> ids)
	{
		if (ids == null) return new ArrayList();
		return findAll(new QueryImpl().in(getEntityMetaData().getIdAttribute().getName(), ids));
	}

	@Override
	public <E extends Entity> Iterable<E> findAll(Iterable<Object> ids, Class<E> clazz)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public <E extends Entity> E findOne(Object id, Class<E> clazz)
	{
		return findAll(Arrays.asList(new Object[]
		{ id }), clazz).iterator().next();
	}

	@Override
	public <E extends Entity> E findOne(Query q, Class<E> clazz)
	{
		return findAll(q, clazz).iterator().next();
	}

	@Override
	public long count()
	{
		return count(new QueryImpl());
	}

	protected String getFromSql()
	{
		StringBuilder from = new StringBuilder();
		from.append(" FROM ").append(getEntityMetaData().getName()).append(" AS this");
		AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			if (att.getDataType() instanceof MrefField)
			{
				// extra join so we can filter on the mrefs
				from.append(" LEFT JOIN ").append(getEntityMetaData().getName()).append('_').append(att.getName())
						.append(" AS ").append(att.getName()).append("_filter ON (this.").append(idAttribute.getName())
						.append(" = ").append(att.getName()).append("_filter.").append(idAttribute.getName())
						.append(") LEFT JOIN ").append(getEntityMetaData().getName()).append('_').append(att.getName())
						.append(" AS ").append(att.getName()).append(" ON (this.").append(idAttribute.getName())
						.append(" = ").append(att.getName()).append('.').append(idAttribute.getName()).append(')');
			}

		return from.toString();
	}

	protected String getCountSql(Query q)
	{
		String where = getWhereSql(q);
		String from = getFromSql();
		String idAttribute = getEntityMetaData().getIdAttribute().getName();
		if (where.length() > 0) return new StringBuilder("SELECT COUNT(DISTINCT this.").append(idAttribute).append(')')
				.append(from).append(' ').append(where).toString();
		return new StringBuilder("SELECT COUNT(DISTINCT this.").append(idAttribute).append(')').append(from).toString();
	}

	protected String getWhereSql(Query q)
	{
		StringBuilder result = new StringBuilder();
		for (QueryRule r : q.getRules())
		{
			StringBuilder predicate = new StringBuilder();
			switch (r.getOperator())
			{
				case SEARCH:
					StringBuilder search = new StringBuilder();
					for (AttributeMetaData att : getEntityMetaData().getAttributes())
					{
						// TODO: other data types???
						if (att.getDataType() instanceof StringField || att.getDataType() instanceof TextField)
						{
							search.append(" OR this.").append(att.getName()).append(" LIKE '%").append(r.getValue())
									.append("%'");
						}
						else
						{
							search.append(" OR CAST(this.").append(att.getName()).append(" as CHAR) LIKE '%")
									.append(r.getValue()).append("%'");
						}
					}
					if (search.length() > 0) result.append('(').append(search.substring(4)).append(')');
					break;
				case AND:
					break;
				case NESTED:
					throw new UnsupportedOperationException();
					// break;
				case OR:
					result.append(" OR ");
					break;
				case IN:
					AttributeMetaData att = getEntityMetaData().getAttribute(r.getField());
					StringBuilder in = new StringBuilder("'UNKNOWN VALUE'");
					List<Object> values = new ArrayList<Object>();
					if (!(r.getValue() instanceof List))
					{
						for (String str : r.getValue().toString().split(","))
							values.add(str);
					}
					else
					{
						values.addAll((Collection<?>) r.getValue());
					}
					boolean quotes = att.getDataType() instanceof StringField || att.getDataType() instanceof TextField;
					for (Object o : values)
					{
						if (quotes) in.append(",'").append(o).append('\'');
						else in.append(',').append(o);
					}

					if (att.getDataType() instanceof MrefField) result.append(att.getName()).append("_filter.")
							.append(r.getField()).append(" IN(").append(in).append(')');
					else result.append("this.").append(r.getField()).append(" IN(").append(in).append(')');
					break;
				default:
					// comparable values...
					att = getEntityMetaData().getAttribute(r.getField());
					if (att == null) throw new RuntimeException("Query failed: attribute '" + r.getField()
							+ "' unknown");
					FieldType type = att.getDataType();
					if (type instanceof MrefField) predicate.append(att.getName()).append("_filter.")
							.append(r.getField());
					else predicate.append("this.").append(r.getField());
					switch (r.getOperator())
					{
						case EQUALS:
							predicate.append(" =");
							break;
						case GREATER:
							predicate.append(" >");
							break;
						case LESS:
							predicate.append(" <");
							break;
						case GREATER_EQUAL:
							predicate.append(" >=");
							break;
						case LESS_EQUAL:
							predicate.append(" <=");
							break;
						default:
							throw new RuntimeException("cannot solve query rule:  " + r);
					}
					if (type instanceof IntField || type instanceof BoolField) predicate.append(' ')
							.append(r.getValue()).append("");
					else predicate.append(" '").append(r.getValue()).append('\'');

					if (result.length() > 0 && !result.toString().endsWith(" OR ")) result.append(" AND ");
					result.append(predicate);
			}
		}
		if (result.length() > 0) return "WHERE " + result.toString().trim();
		else return "";
	}

	protected String getSortSql(Query q)
	{
		StringBuilder sortSql = new StringBuilder();
		if (q.getSort() != null)
		{
			for (Sort.Order o : q.getSort())
			{
				AttributeMetaData att = getEntityMetaData().getAttribute(o.getProperty());
				if (att.getDataType() instanceof MrefField) sortSql.append(", ").append(att.getName());
				else sortSql.append(", ").append(att.getName());
				if (o.getDirection().equals(Sort.Direction.DESC))
				{
					sortSql.append(" DESC");
				}
				else
				{
					sortSql.append(" ASC");
				}
			}

			if (sortSql.length() > 0) sortSql = new StringBuilder("ORDER BY ").append(sortSql.substring(2));
		}
		return sortSql.toString();
	}

	private String formatValue(AttributeMetaData att, Object value)
	{
		if (att.getDataType() instanceof StringField || att.getDataType() instanceof TextField)
		{
			return "'" + value + "'";
		}
		else
		{
			return value.toString();
		}
	}

	public MysqlRepositoryQuery query()
	{
		return new MysqlRepositoryQuery(this);
	}

	@Override
	public void update(Entity entity)
	{
		update(Arrays.asList(new Entity[]
		{ entity }));
	}

	@Override
	public void update(Iterable<? extends Entity> entities)
	{
		AtomicInteger count = new AtomicInteger(0);

		// TODO, split in subbatches
		final List<Entity> batch = new ArrayList<Entity>();
		if (entities != null) for (Entity e : entities)
		{
			batch.add(e);
			count.addAndGet(1);
		}
		final AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();
		final List<Object> ids = new ArrayList<Object>();
		final Map<String, List<Entity>> mrefs = new HashMap<String, List<Entity>>();

		jdbcTemplate.batchUpdate(this.getUpdateSql(), new BatchPreparedStatementSetter()
		{
			@Override
			public void setValues(PreparedStatement preparedStatement, int rowIndex) throws SQLException
			{
				Entity e = batch.get(rowIndex);
				logger.debug("updating: " + e);
				Object idValue = idAttribute.getDataType().convert(e.get(idAttribute.getName()));
				ids.add(idValue);
				int fieldIndex = 1;
				for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
				{
					// create the mref records
					if (att.getDataType() instanceof MrefField)
					{
						if (mrefs.get(att.getName()) == null) mrefs.put(att.getName(), new ArrayList<Entity>());
						if (e.get(att.getName()) != null) for (Object val : e.getList(att.getName()))
						{
							Entity mref = new MapEntity();
							mref.set(idAttribute.getName(), idValue);
							mref.set(att.getName(), val);
							mrefs.get(att.getName()).add(mref);
						}
					}
					else
					{
						// default value, if any
						if (e.get(att.getName()) == null)
						{
							preparedStatement.setObject(fieldIndex++, att.getDefaultValue());
						}
						else
						{
							if (att.getDataType() instanceof XrefField)
							{
								Object value = e.get(att.getName());
								if (value instanceof Entity)
								{
									preparedStatement.setObject(
											fieldIndex++,
											att.getRefEntity()
													.getIdAttribute()
													.getDataType()
													.convert(
															((Entity) value).get(att.getRefEntity().getIdAttribute()
																	.getName())));
								}
								else
								{
									preparedStatement.setObject(fieldIndex++, att.getRefEntity().getIdAttribute()
											.getDataType().convert(value));
								}
							}
							else
							{
								preparedStatement.setObject(fieldIndex++,
										att.getDataType().convert(e.get(att.getName())));
							}
						}
					}
				}
				preparedStatement.setObject(fieldIndex++, idValue);
			}

			@Override
			public int getBatchSize()
			{
				return batch.size();
			}
		});

		// update mrefs
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
		{
			if (att.getDataType() instanceof MrefField)
			{
				removeMrefs(ids, att);
				addMrefs(mrefs.get(att.getName()), att);
			}
		}

		// return count.get();
	}

	protected String getUpdateSql()
	{
		// use (readonly) identifier
		AttributeMetaData idAttribute = getEntityMetaData().getIdAttribute();

		// create sql
		StringBuilder sql = new StringBuilder("UPDATE ").append(this.getName()).append(" SET ");
		String params = "";
		for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			if (!(att.getDataType() instanceof MrefField))
			{
				sql.append(att.getName()).append(" = ?, ");
			}
		if (sql.charAt(sql.length() - 1) == ' ' && sql.charAt(sql.length() - 2) == ',')
		{
			sql.setLength(sql.length() - 2);
		}
		sql.append(" WHERE ").append(idAttribute.getName()).append("= ?");
		return sql.toString();
	}

	@Override
	public void delete(Entity entity)
	{
		this.delete(Arrays.asList(new Entity[]
		{ entity }));

	}

	@Override
	public void delete(Iterable<? extends Entity> entities)
	{
		// todo, split in subbatchs
		final List<Object> batch = new ArrayList<Object>();
		for (Entity e : entities)
			batch.add(e.getIdValue());
		this.deleteById(batch);
	}

	public String getDeleteSql()
	{
		return "DELETE FROM " + getName() + " WHERE " + getEntityMetaData().getIdAttribute().getName() + " = ?";
	}

	@Override
	public void deleteById(Object id)
	{
		this.deleteById(Arrays.asList(new Object[]
		{ id }));
	}

	@Override
	public void deleteById(Iterable<Object> ids)
	{
		final List<Object> idList = new ArrayList<Object>();
		for (Object id : ids)
			idList.add(id);

		jdbcTemplate.batchUpdate(getDeleteSql(), new BatchPreparedStatementSetter()
		{
			@Override
			public void setValues(PreparedStatement preparedStatement, int i) throws SQLException
			{
				preparedStatement.setObject(1, idList.get(i));
			}

			@Override
			public int getBatchSize()
			{
				return idList.size();
			}
		});
	}

	@Override
	public void deleteAll()
	{
		delete(this);
	}

	@Override
	public void update(List<? extends Entity> entities, DatabaseAction dbAction, String... keyName)
	{
		throw new UnsupportedOperationException();
	}

	public MysqlRepositoryCollection getRepositoryCollection()
	{
		return repositoryCollection;
	}

	@Override
	public AggregateResult aggregate(AttributeMetaData xAttr, AttributeMetaData yAttr, Query q)
	{
		throw new UnsupportedOperationException("not yet implemented");
	}

	private class EntityMapper implements RowMapper
	{

		@Override
		public Entity mapRow(ResultSet resultSet, int i) throws SQLException
		{
			Entity e = new MysqlEntity(getEntityMetaData(), getRepositoryCollection());

			for (AttributeMetaData att : getEntityMetaData().getAtomicAttributes())
			{
				if (att.getDataType() instanceof MrefField)
				{
					// TODO: convert to typed lists (or arrays?)
					if (att.getRefEntity().getIdAttribute().getDataType() instanceof IntField)
					{
						e.set(att.getName(), DataConverter.toIntList(resultSet.getString(att.getName())));
					}
					else
					{
						e.set(att.getName(), DataConverter.toObjectList(resultSet.getString(att.getName())));
					}

				}
				else if (att.getDataType() instanceof XrefField)
				{
					e.set(att.getName(),
							att.getRefEntity().getIdAttribute().getDataType()
									.convert(resultSet.getObject(att.getName())));
				}
				else
				{
					e.set(att.getName(), att.getDataType().convert(resultSet.getObject(att.getName())));
				}
			}
			return e;
		}
	}
}
