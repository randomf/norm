package com.dieselpoint.norm.sqlmakers;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;

import com.dieselpoint.norm.DbException;
import com.dieselpoint.norm.serialize.DbSerializable;
import com.dieselpoint.norm.serialize.DbSerializer;

/**
 * Provides means of reading and writing properties in a pojo.
 */
public class StandardPojoInfo implements PojoInfo {
	
	/*
	 * annotations recognized: @ Id, @ GeneratedValue @ Transient @ Table @ Column @ DbSerializer @ Enumerated
	 */

	LinkedHashMap<String, Property> propertyMap = new LinkedHashMap<String, Property>();
	String table;
	String primaryKeyName;
	String generatedColumnName;
	
	String insertSql;
	int insertSqlArgCount;
	String [] insertColumnNames;

	String upsertSql;
	int upsertSqlArgCount;
	String [] upsertColumnNames;
	
	String updateSql;
	String[] updateColumnNames;
	int updateSqlArgCount;
	
	String selectColumns;
	
	public static class Property {
		public String name;
		public Method readMethod;
		public Method writeMethod;
		public Field field;
		public Class<?> dataType;
		public boolean isGenerated;
		public boolean isPrimaryKey;
		public boolean isEnumField;
		public Class<Enum> enumClass;
		public EnumType enumType;
		public Column columnAnnotation;
		public DbSerializable serializer;
	}

	public StandardPojoInfo(Class<?> clazz) {

		try {
			
			if (Map.class.isAssignableFrom(clazz)) {
				//leave properties empty
			} else {
				populateProperties(clazz);
			}

			Table annot = (Table) clazz.getAnnotation(Table.class);
			if (annot != null) {
				table = annot.name();
			} else {
				table = clazz.getSimpleName();
			}

		} catch (Throwable t) {
			throw new DbException(t);
		}
	}
	
	
	
	private void populateProperties(Class<?> clazz) throws IntrospectionException, InstantiationException, IllegalAccessException {
		for (Field field : clazz.getFields()) {
			int modifiers = field.getModifiers();

			if (Modifier.isPublic(modifiers)) {

				if (Modifier.isStatic(modifiers)
						|| Modifier.isFinal(modifiers)) {
					continue;
				}

				if (field.getAnnotation(Transient.class) != null) {
					continue;
				}

				Property prop = new Property();
				prop.name = field.getName();
				prop.field = field;
				prop.dataType = field.getType();

				applyAnnotations(prop, field);

				propertyMap.put(prop.name, prop);
			}
		}

		BeanInfo beanInfo = Introspector.getBeanInfo(clazz, Object.class);
		PropertyDescriptor[] descriptors = beanInfo
				.getPropertyDescriptors();
		for (PropertyDescriptor descriptor : descriptors) {

			Method readMethod = descriptor.getReadMethod();
			if (readMethod == null) {
				continue;
			}
			if (readMethod.getAnnotation(Transient.class) != null) {
				continue;
			}
			
			Property prop = new Property();
			prop.name = descriptor.getName();
			prop.readMethod = readMethod;
			prop.writeMethod = descriptor.getWriteMethod();
			prop.dataType = descriptor.getPropertyType();

			applyAnnotations(prop, prop.readMethod);
			
			propertyMap.put(prop.name, prop);
		}
	}


	/**
	 * Apply the annotations on the field or getter method to the property.
	 * @throws IllegalAccessException 
	 * @throws InstantiationException 
	 */
	private void applyAnnotations(Property prop, AnnotatedElement ae) throws InstantiationException, IllegalAccessException {
		
		Column col = ae.getAnnotation(Column.class);
		if (col != null) {
			String name = col.name().trim();
			if (name.length() > 0) {
				prop.name = name;
			}
			prop.columnAnnotation = col;
		}
		
		if (ae.getAnnotation(Id.class) != null) {
			prop.isPrimaryKey = true;
			primaryKeyName = prop.name;
		}

		if (ae.getAnnotation(GeneratedValue.class) != null) {
			generatedColumnName = prop.name;
			prop.isGenerated = true;
		}

		if (prop.dataType.isEnum()) {
			prop.isEnumField = true;
			prop.enumClass = (Class<Enum>) prop.dataType;
			/* We default to STRING enum type. Can be overriden with @Enumerated annotation */
			prop.enumType = EnumType.STRING;
			if (ae.getAnnotation(Enumerated.class) != null) {
				prop.enumType = ae.getAnnotation(Enumerated.class).value();
			}
		}
		
		DbSerializer sc = ae.getAnnotation(DbSerializer.class);
		if (sc != null) {
			prop.serializer = sc.value().newInstance();
		}
		
	}


/*
	private Method getMethod(Method meth, String propertyName, Property pair) {
		if (meth == null) {
			return null;
		}
		if (meth.getAnnotation(Transient.class) != null) {
			return null;
		}
		if (meth.getAnnotation(Id.class) != null) {
			this.primaryKeyName = propertyName;
			pair.isPrimaryKey = true;
		}
		if (meth.getAnnotation(GeneratedValue.class) != null) {
			this.generatedColumnName = propertyName;
			pair.isGenerated = true;
		}
		return meth;
	}
*/
	
	
	public Object getValue(Object pojo, String name) {

		try {

			Property prop = propertyMap.get(name);
			if (prop == null) {
				throw new DbException("No such field: " + name);
			}
			
			Object value = null;
			
			if (prop.readMethod != null) {
				value = prop.readMethod.invoke(pojo);
				
			} else if (prop.field != null) {
				value = prop.field.get(pojo);
			}
			
			if (value != null) {
				if (prop.serializer != null) {
					value =  prop.serializer.serialize(value);
				
				} else if (prop.isEnumField) {
					// handle enums according to selected enum type
					if (prop.enumType == EnumType.ORDINAL) {
						value = ((Enum) value).ordinal();
					}
					// EnumType.STRING and others (if present in the future)
					else {
						value = value.toString();
					}					
				}	
			}

			return value;

		} catch (Throwable t) {
			throw new DbException(t);
		}
	}	

	public void putValue(Object pojo, String name, Object value) {

		Property prop = propertyMap.get(name);
		if (prop == null) {
			throw new DbException("No such field: " + name);
		}

		if (value != null) {
			// Perform deserialization if non-trivial data type is retrieved 
			if (prop.serializer != null) {
				value = prop.serializer.deserialize((String) value, prop.dataType);
			// Convert value to enum type
			} else if (prop.isEnumField) {
				value = getEnumConst(prop.enumClass, prop.enumType, value);
			// Convert Integer to Long if POJO write method expects Long
			} else if (value.getClass().equals(Integer.class) && prop.writeMethod.getParameterTypes().length == 1
					&& prop.writeMethod.getParameterTypes()[0].equals(Long.class)) {
				value = Long.valueOf(((Integer) value).longValue());
			
			// Convert Long to Integer if POJO write method expects Integer and the Long value doesn't overflow
			} else if (value.getClass().equals(Long.class) && prop.writeMethod.getParameterTypes().length == 1
					&& prop.writeMethod.getParameterTypes()[0].equals(Integer.class)) {				
				// Check for overflow
				long valueAsLong = ((Long) value).longValue();  
				if (valueAsLong > Integer.valueOf(Integer.MAX_VALUE).longValue()) {
					throw new DbException("Provided value: " + value + " for write method " + prop.writeMethod.toString() 
							+ " has overflown."); 
				}
				// Check for underflow
				if (valueAsLong < Integer.valueOf(Integer.MIN_VALUE).longValue()) {
					throw new DbException("Provided value: " + value + " for write method " + prop.writeMethod.toString() 
							+ " has underflown."); 
				}
				// Convert if ok
				value = Integer.valueOf(((Long) value).intValue());
			}
			
		}

		if (prop.writeMethod != null) {
			try {				
				prop.writeMethod.invoke(pojo, value);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				throw new DbException("Could not write value into pojo. Property: " + prop.name + " of type: " 
						+ value.getClass().getCanonicalName() + " to method: " + prop.writeMethod.toString() 
						+ " value: " + value, e);
			}
			return;
		}

		if (prop.field != null) {
			try {
				prop.field.set(pojo, value);
			} catch (IllegalArgumentException | IllegalAccessException e) {
				throw new DbException("Could not set value into pojo. Field: " + prop.field.toString() + " value: " + value, e);
			}
			return;
		}

	}

	/**
	 * Convert a string to an enum const of the appropriate class.
	 */
	private <T extends Enum<T>> Object getEnumConst(Class<T> enumType, EnumType type, Object value) {
		String str = value.toString();
		if (type == EnumType.ORDINAL) {
			Integer ordinalValue = (Integer) value;
			if (ordinalValue < 0 || ordinalValue >= enumType.getEnumConstants().length) {
				throw new DbException("Invalid ordinal number " + ordinalValue + " for enum class " + enumType.getCanonicalName());
			}
			return enumType.getEnumConstants()[ordinalValue];
		}
		else {		
			for (T e: enumType.getEnumConstants()) {
				if (str.equals(e.toString())) {
					return e;
				}
			}
			throw new DbException("Enum value does not exist. value:" + str);
		}
	}
	
	
	

	@Override
	public void populateGeneratedKey(ResultSet generatedKeys, Object insertRow) {

		try {

			//StandardPojoInfo pojoInfo = getPojoInfo(insertRow.getClass());
			Property prop = propertyMap.get(generatedColumnName);

			Object newKey;
			if (prop.dataType.isAssignableFrom(int.class)) {
				newKey = generatedKeys.getInt(1);
			} else {
				newKey = generatedKeys.getLong(1);
			}

			putValue(insertRow, generatedColumnName, newKey);

		} catch (Throwable t) {
			throw new DbException(t);
		}
	}
	
	
}
