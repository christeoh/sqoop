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

package org.apache.sqoop.mapreduce;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import org.apache.avro.Schema;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.sqoop.mapreduce.hcat.SqoopHCatUtilities;
import org.apache.sqoop.SqoopOptions;
import org.apache.sqoop.config.ConfigurationHelper;
import org.apache.sqoop.lib.LargeObjectLoader;
import org.apache.sqoop.manager.ConnManager;
import org.apache.sqoop.manager.ImportJobContext;
import org.apache.sqoop.mapreduce.ImportJobBase;
import org.apache.sqoop.mapreduce.db.DBConfiguration;
import org.apache.sqoop.mapreduce.db.DataDrivenDBInputFormat;
import org.apache.sqoop.mapreduce.parquet.ParquetImportJobConfigurator;
import org.apache.sqoop.orm.AvroSchemaGenerator;
import static org.apache.sqoop.mapreduce.parquet.ParquetConstants.SQOOP_PARQUET_AVRO_SCHEMA_KEY;

/**
 * Actually runs a jdbc import job using the ORM files generated by the
 * sqoop.orm package. Uses DataDrivenDBInputFormat.
 */
public class DataDrivenImportJob extends ImportJobBase {

  public static final Log LOG = LogFactory.getLog(
      DataDrivenImportJob.class.getName());

  private final ParquetImportJobConfigurator parquetImportJobConfigurator;

  public DataDrivenImportJob(final SqoopOptions opts,
      final Class<? extends InputFormat> inputFormatClass,
      ImportJobContext context, ParquetImportJobConfigurator parquetImportJobConfigurator) {
    super(opts, null, inputFormatClass, null, context);
    this.parquetImportJobConfigurator = parquetImportJobConfigurator;
  }

  public DataDrivenImportJob(final SqoopOptions opts,
      final Class<? extends InputFormat> inputFormatClass,
      ImportJobContext context) {
    this(opts, inputFormatClass, context, null);
  }

  @SuppressWarnings("unchecked")
  public DataDrivenImportJob(final SqoopOptions opts) {
    this(opts, DataDrivenDBInputFormat.class, null);
  }

  @Override
  protected void configureMapper(Job job, String tableName,
      String tableClassName) throws IOException {
    if (isHCatJob) {
      LOG.info("Configuring mapper for HCatalog import job");
      job.setOutputKeyClass(LongWritable.class);
      job.setOutputValueClass(SqoopHCatUtilities.getImportValueClass());
      job.setMapperClass(SqoopHCatUtilities.getImportMapperClass());
      return;
    }
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      // For text files, specify these as the output types; for
      // other types, we just use the defaults.
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(NullWritable.class);
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.AvroDataFile) {
      final String schemaNameOverride = null;
      Schema schema = generateAvroSchema(tableName, schemaNameOverride);
      try {
        writeAvroSchema(schema);
      } catch (final IOException e) {
        LOG.error("Error while writing Avro schema.", e);
      }

      AvroJob.setMapOutputSchema(job.getConfiguration(), schema);
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.ParquetFile) {
      // Kite SDK requires an Avro schema to represent the data structure of
      // target dataset. If the schema name equals to generated java class name,
      // the import will fail. So we use table name as schema name and add a
      // prefix "codegen_" to generated java class to avoid the conflict.
      final String schemaNameOverride = tableName;
      Schema schema = generateAvroSchema(tableName, schemaNameOverride);
      Path destination = getContext().getDestination();

      options.getConf().set(SQOOP_PARQUET_AVRO_SCHEMA_KEY, schema.toString());
      parquetImportJobConfigurator.configureMapper(job, schema, options, tableName, destination);
    }

    job.setMapperClass(getMapperClass());
  }

  private Schema generateAvroSchema(String tableName,
      String schemaNameOverride) throws IOException {
    ConnManager connManager = getContext().getConnManager();
    AvroSchemaGenerator generator = new AvroSchemaGenerator(options,
        connManager, tableName);
    return generator.generate(schemaNameOverride);
  }

  private void writeAvroSchema(final Schema schema) throws IOException {
    // Generate schema in JAR output directory.
    final File schemaFile = new File(options.getJarOutputDir(), schema.getName() + ".avsc");

    LOG.info("Writing Avro schema file: " + schemaFile);
    FileUtils.forceMkdir(schemaFile.getParentFile());
    FileUtils.writeStringToFile(schemaFile, schema.toString(true));

    // Copy schema to code output directory.
    try {
      FileUtils.moveFileToDirectory(schemaFile, new File(options.getCodeOutputDir()), true);
    } catch (final IOException e) {
      LOG.debug("Could not move Avro schema file to code output directory.", e);
    }
  }

  @Override
  protected Class<? extends Mapper> getMapperClass() {
    if (options.getHCatTableName() != null) {
      return SqoopHCatUtilities.getImportMapperClass();
    }
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      return TextImportMapper.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.SequenceFile) {
      return SequenceFileImportMapper.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.AvroDataFile) {
      return AvroImportMapper.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.ParquetFile) {
      return parquetImportJobConfigurator.getMapperClass();
    }

    return null;
  }

  @Override
  protected Class<? extends OutputFormat> getOutputFormatClass()
      throws ClassNotFoundException {
    if (isHCatJob) {
      LOG.debug("Returning HCatOutputFormat for output format");
      return SqoopHCatUtilities.getOutputFormatClass();
    }
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      return RawKeyTextOutputFormat.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.SequenceFile) {
      return SequenceFileOutputFormat.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.AvroDataFile) {
      return AvroOutputFormat.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.ParquetFile) {
      return parquetImportJobConfigurator.getOutputFormatClass();
    }

    return null;
  }

  /**
   * Build the boundary query for the column of the result set created by
   * the given query.
   * @param col column name whose boundaries we're interested in.
   * @param query sub-query used to create the result set.
   * @return input boundary query as a string
   */
  private String buildBoundaryQuery(String col, String query) {
    if (col == null || options.getNumMappers() == 1) {
      return "";
    }

    // Replace table name with alias 't1' if column name is a fully
    // qualified name.  This is needed because "tableName"."columnName"
    // in the input boundary query causes a SQL syntax error in most dbs
    // including Oracle and MySQL.
    String alias = "t1";
    int dot = col.lastIndexOf('.');
    String qualifiedName = (dot == -1) ? col : alias + col.substring(dot);

    ConnManager mgr = getContext().getConnManager();
    String ret = mgr.getInputBoundsQuery(qualifiedName, query);
    if (ret != null) {
      return ret;
    }

    return "SELECT MIN(" + qualifiedName + "), MAX(" + qualifiedName + ") "
        + "FROM (" + query + ") AS " + alias;
  }

  @Override
  protected void configureInputFormat(Job job, String tableName,
      String tableClassName, String splitByCol) throws IOException {
    ConnManager mgr = getContext().getConnManager();
    try {
      String username = options.getUsername();
      if (null == username || username.length() == 0) {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(), options.getConnectString(),
            options.getFetchSize(), options.getConnectionParams());
      } else {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(), options.getConnectString(),
            username, options.getPassword(), options.getFetchSize(),
            options.getConnectionParams());
      }

      if (null != tableName) {
        // Import a table.
        String [] colNames = options.getColumns();
        if (null == colNames) {
          colNames = mgr.getColumnNames(tableName);
        }

        String [] sqlColNames = null;
        if (null != colNames) {
          sqlColNames = new String[colNames.length];
          for (int i = 0; i < colNames.length; i++) {
            sqlColNames[i] = mgr.escapeColName(colNames[i]);
          }
        }

        // It's ok if the where clause is null in DBInputFormat.setInput.
        String whereClause = options.getWhereClause();

        // We can't set the class properly in here, because we may not have the
        // jar loaded in this JVM. So we start by calling setInput() with
        // DBWritable and then overriding the string manually.
        DataDrivenDBInputFormat.setInput(job, DBWritable.class,
            mgr.escapeTableName(tableName), whereClause,
            mgr.escapeColName(splitByCol), sqlColNames);

        // If user specified boundary query on the command line propagate it to
        // the job
        if (options.getBoundaryQuery() != null) {
          DataDrivenDBInputFormat.setBoundingQuery(job.getConfiguration(),
                  options.getBoundaryQuery());
        }
      } else {
        // Import a free-form query.
        String inputQuery = options.getSqlQuery();
        String sanitizedQuery = inputQuery.replace(
            DataDrivenDBInputFormat.SUBSTITUTE_TOKEN, " (1 = 1) ");

        String inputBoundingQuery = options.getBoundaryQuery();
        if (inputBoundingQuery == null) {
          inputBoundingQuery = buildBoundaryQuery(splitByCol, sanitizedQuery);
        }
        DataDrivenDBInputFormat.setInput(job, DBWritable.class,
            inputQuery, inputBoundingQuery);
        new DBConfiguration(job.getConfiguration()).setInputOrderBy(
            splitByCol);
      }
      if (options.getRelaxedIsolation()) {
        LOG
          .info("Enabling relaxed (read uncommitted) transaction "
             + "isolation for imports");
        job.getConfiguration()
          .setBoolean(DBConfiguration.PROP_RELAXED_ISOLATION, true);
      }
      LOG.debug("Using table class: " + tableClassName);
      job.getConfiguration().set(ConfigurationHelper.getDbInputClassProperty(),
          tableClassName);

      job.getConfiguration().setLong(LargeObjectLoader.MAX_INLINE_LOB_LEN_KEY,
          options.getInlineLobLimit());

      if (options.getSplitLimit() != null) {
        org.apache.sqoop.config.ConfigurationHelper.setSplitLimit(
          job.getConfiguration(), options.getSplitLimit());
      }

      LOG.debug("Using InputFormat: " + inputFormatClass);
      job.setInputFormatClass(inputFormatClass);
    } finally {
      try {
        mgr.close();
      } catch (SQLException sqlE) {
        LOG.warn("Error closing connection: " + sqlE);
      }
    }
  }
}

